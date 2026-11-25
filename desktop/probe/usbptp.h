// PTP straight over USB with libusb (the camera's driver swapped to WinUSB with Zadig).
#pragma once
#include <libusb.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <functional>

struct UsbPtp {
    libusb_context* ctx = nullptr;
    libusb_device_handle* h = nullptr;
    uint8_t epIn = 0, epOut = 0;
    int iface = -1;
    int maxPkt = 512;
    uint32_t tid = 0;
    std::vector<uint8_t> leftover;
    std::string why;   // what went wrong, in words
    uint16_t pid = 0;

    struct Result {
        bool ok = false;        // the exchange itself worked
        uint16_t code = 0;      // PTP response code (0x2001 = OK)
        std::vector<uint8_t> data;
        std::string err;
    };

    ~UsbPtp() { close(); }

    void close() {
        if (h) {
            if (iface >= 0) libusb_release_interface(h, iface);
            libusb_close(h);
            h = nullptr;
        }
        if (ctx) { libusb_exit(ctx); ctx = nullptr; }
    }

    /** Finds the Fujifilm camera (vendor 04CB) and claims its still-image interface. */
    bool open() {
        if (libusb_init(&ctx) != 0) { why = "libusb 初始化失敗"; return false; }
        libusb_device** list = nullptr;
        ssize_t n = libusb_get_device_list(ctx, &list);
        libusb_device* found = nullptr;
        for (ssize_t i = 0; i < n; i++) {
            libusb_device_descriptor d;
            if (libusb_get_device_descriptor(list[i], &d) == 0 && d.idVendor == 0x04CB) { found = list[i]; pid = d.idProduct; break; }
        }
        if (!found) { libusb_free_device_list(list, 1); why = "USB 上沒有富士相機（廠商 04CB）"; return false; }
        int rc = libusb_open(found, &h);
        if (rc != 0) {
            libusb_free_device_list(list, 1);
            why = std::string("打不開相機：") + libusb_error_name(rc) +
                  (rc == LIBUSB_ERROR_NOT_SUPPORTED || rc == LIBUSB_ERROR_ACCESS ? "（驅動還不是 WinUSB，請照步驟用 Zadig 換）" : "");
            return false;
        }
        libusb_config_descriptor* cfg = nullptr;
        libusb_get_active_config_descriptor(found, &cfg);
        libusb_free_device_list(list, 1);
        if (!cfg) { why = "讀不到 USB 設定"; return false; }
        for (int i = 0; i < cfg->bNumInterfaces && iface < 0; i++) {
            const libusb_interface_descriptor& alt = cfg->interface[i].altsetting[0];
            uint8_t in = 0, out = 0;
            int mp = 512;
            for (int e = 0; e < alt.bNumEndpoints; e++) {
                const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                if ((ep.bmAttributes & 3) != LIBUSB_TRANSFER_TYPE_BULK) continue;
                if (ep.bEndpointAddress & 0x80) in = ep.bEndpointAddress;
                else { out = ep.bEndpointAddress; mp = ep.wMaxPacketSize; }
            }
            if (in && out) { iface = alt.bInterfaceNumber; epIn = in; epOut = out; maxPkt = mp ? mp : 512; }
        }
        libusb_free_config_descriptor(cfg);
        if (iface < 0) { why = "找不到相機的資料通道"; return false; }
        rc = libusb_claim_interface(h, iface);
        if (rc != 0) { why = std::string("相機被別的程式佔用：") + libusb_error_name(rc); iface = -1; return false; }
        return true;
    }

    static void put16(std::vector<uint8_t>& b, uint16_t v) { b.push_back(v & 0xFF); b.push_back(v >> 8); }
    static void put32(std::vector<uint8_t>& b, uint32_t v) { for (int i = 0; i < 4; i++) b.push_back((v >> (8 * i)) & 0xFF); }
    static uint32_t get32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
    static uint16_t get16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }

    bool writeAll(const uint8_t* p, size_t n, std::function<void(uint64_t, uint64_t)> progress, std::string& err) {
        const size_t slice = 1024 * 1024;   // a multiple of every packet size
        size_t done = 0;
        while (done < n) {
            int want = (int)std::min(slice, n - done);
            int got = 0;
            int rc = libusb_bulk_transfer(h, epOut, const_cast<uint8_t*>(p + done), want, &got, 30000);
            if (rc != 0) { err = std::string("寫入失敗：") + libusb_error_name(rc); return false; }
            done += got;
            if (progress) progress(done, n);
        }
        if (n % maxPkt == 0) {
            int got = 0;
            libusb_bulk_transfer(h, epOut, nullptr, 0, &got, 5000);
        }
        return true;
    }

    bool readSome(std::vector<uint8_t>& into, size_t want, int timeout, std::string& err) {
        if (!leftover.empty()) { into.swap(leftover); leftover.clear(); return true; }
        size_t size = ((std::max<size_t>(want, maxPkt) + maxPkt - 1) / maxPkt) * maxPkt;
        size = std::min<size_t>(size, 1024 * 1024);
        into.resize(size);
        int got = 0;
        int rc = libusb_bulk_transfer(h, epIn, into.data(), (int)size, &got, timeout);
        if (rc != 0 && !(rc == LIBUSB_ERROR_TIMEOUT && got > 0)) { err = std::string("讀取失敗：") + libusb_error_name(rc); return false; }
        into.resize(got);
        return true;
    }

    /** One container from the camera: type, code, payload. */
    bool readContainer(uint16_t& type, uint16_t& code, std::vector<uint8_t>& payload, int timeout,
                       std::function<void(uint64_t, uint64_t)> progress, std::string& err) {
        std::vector<uint8_t> acc, piece;
        while (acc.size() < 12) {
            if (!readSome(piece, 512, timeout, err)) return false;
            acc.insert(acc.end(), piece.begin(), piece.end());
            if (piece.empty()) continue;
        }
        uint32_t total = get32(acc.data());
        if (total < 12) { err = "PTP 長度異常"; return false; }
        while (acc.size() < total) {
            if (!readSome(piece, total - acc.size(), timeout, err)) return false;
            acc.insert(acc.end(), piece.begin(), piece.end());
            if (progress) progress(std::min<size_t>(acc.size(), total), total);
        }
        if (acc.size() > total) leftover.assign(acc.begin() + total, acc.end());
        type = get16(acc.data() + 4);
        code = get16(acc.data() + 6);
        payload.assign(acc.begin() + 12, acc.begin() + total);
        return true;
    }

    Result cmd(uint16_t op, std::vector<uint32_t> params, const std::vector<uint8_t>* dataOut = nullptr,
               std::function<void(uint64_t, uint64_t)> progress = nullptr, int timeout = 30000) {
        Result r;
        uint32_t t = ++tid;
        std::vector<uint8_t> c;
        put32(c, 12 + 4 * (uint32_t)params.size());
        put16(c, 1); put16(c, op); put32(c, t);
        for (auto p : params) put32(c, p);
        if (!writeAll(c.data(), c.size(), nullptr, r.err)) return r;
        if (dataOut) {
            std::vector<uint8_t> d;
            d.reserve(12 + dataOut->size());
            put32(d, 12 + (uint32_t)dataOut->size());
            put16(d, 2); put16(d, op); put32(d, t);
            d.insert(d.end(), dataOut->begin(), dataOut->end());
            if (!writeAll(d.data(), d.size(), progress, r.err)) return r;
        }
        for (int i = 0; i < 4; i++) {
            uint16_t type = 0, code = 0;
            std::vector<uint8_t> payload;
            if (!readContainer(type, code, payload, timeout, dataOut ? nullptr : progress, r.err)) return r;
            if (type == 2) { r.data.swap(payload); continue; }
            if (type == 3) { r.ok = true; r.code = code; return r; }
        }
        r.err = "沒有收到相機的回應";
        return r;
    }
};
