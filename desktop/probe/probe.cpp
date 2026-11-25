// 富士食堂 USB 測試：看 Windows 能不能不換驅動，直接透過系統的相機介面（WPD）跟富士相機講 PTP。
// 1. 找到富士相機並打開
// 2. 讀相機資訊（PTP GetDeviceInfo），看它支不支援 RAW 轉檔用的指令
// 3. 如果旁邊放了一個 .RAF（或把 .RAF 拖到這個程式上），就真的請相機烘焙一張，成品存成 JPG
// 結果同時寫進「富士食堂USB測試結果.txt」。

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define _CRT_SECURE_NO_WARNINGS
#include <algorithm>
#include <cstdarg>
#include <cstring>
#include <windows.h>
#include <objbase.h>
#include <PortableDeviceApi.h>
#include <PortableDevice.h>
#include <WpdMtpExtensions.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cstdint>
#include <chrono>
#include <thread>

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "PortableDeviceGUIDs.lib")

static FILE* g_log = nullptr;

static std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(n, 0);
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, nullptr, nullptr);
    return s;
}

static void say(const char* fmt, ...) {
    char buf[4096];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    fputs(buf, stdout);
    fputs("\n", stdout);
    fflush(stdout);
    if (g_log) { fputs(buf, g_log); fputs("\n", g_log); fflush(g_log); }
}

static std::string hr(HRESULT h) {
    char b[32];
    snprintf(b, sizeof b, "0x%08lX", (unsigned long)h);
    return b;
}

// ---------------------------------------------------------------- PTP over WPD

struct Ptp {
    IPortableDevice* dev = nullptr;

    struct Result {
        HRESULT hr = E_FAIL;
        uint32_t code = 0;            // PTP response code (0x2001 = OK)
        std::vector<uint8_t> data;    // data phase from the camera
    };

    IPortableDeviceValues* newValues() {
        IPortableDeviceValues* v = nullptr;
        CoCreateInstance(CLSID_PortableDeviceValues, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&v));
        return v;
    }

    IPortableDevicePropVariantCollection* paramList(const std::vector<uint32_t>& params) {
        IPortableDevicePropVariantCollection* c = nullptr;
        CoCreateInstance(CLSID_PortableDevicePropVariantCollection, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&c));
        for (uint32_t p : params) {
            PROPVARIANT pv;
            PropVariantInit(&pv);
            pv.vt = VT_UI4;
            pv.ulVal = p;
            c->Add(&pv);
        }
        return c;
    }

    HRESULT send(IPortableDeviceValues* in, IPortableDeviceValues** out) {
        HRESULT h = dev->SendCommand(0, in, out);
        if (FAILED(h)) return h;
        HRESULT inner = S_OK;
        (*out)->GetErrorValue(WPD_PROPERTY_COMMON_HRESULT, &inner);
        return inner;
    }

    IPortableDeviceValues* command(const PROPERTYKEY& cmd, uint16_t op, const std::vector<uint32_t>& params) {
        IPortableDeviceValues* in = newValues();
        in->SetGuidValue(WPD_PROPERTY_COMMON_COMMAND_CATEGORY, cmd.fmtid);
        in->SetUnsignedIntegerValue(WPD_PROPERTY_COMMON_COMMAND_ID, cmd.pid);
        in->SetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_OPERATION_CODE, op);
        IPortableDevicePropVariantCollection* p = paramList(params);
        in->SetIPortableDevicePropVariantCollectionValue(WPD_PROPERTY_MTP_EXT_OPERATION_PARAMS, p);
        p->Release();
        return in;
    }

    void simpleCmd(const PROPERTYKEY& cmd, IPortableDeviceValues* in) {
        in->SetGuidValue(WPD_PROPERTY_COMMON_COMMAND_CATEGORY, cmd.fmtid);
        in->SetUnsignedIntegerValue(WPD_PROPERTY_COMMON_COMMAND_ID, cmd.pid);
    }

    uint32_t endTransfer(LPWSTR context, HRESULT* h) {
        IPortableDeviceValues* in = newValues();
        simpleCmd(WPD_COMMAND_MTP_EXT_END_DATA_TRANSFER, in);
        in->SetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, context);
        IPortableDeviceValues* out = nullptr;
        *h = send(in, &out);
        ULONG code = 0;
        if (out) { out->GetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_RESPONSE_CODE, &code); out->Release(); }
        in->Release();
        return code;
    }

    Result noData(uint16_t op, const std::vector<uint32_t>& params) {
        Result r;
        IPortableDeviceValues* in = command(WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITHOUT_DATA_PHASE, op, params);
        IPortableDeviceValues* out = nullptr;
        r.hr = send(in, &out);
        ULONG code = 0;
        if (out) { out->GetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_RESPONSE_CODE, &code); out->Release(); }
        r.code = code;
        in->Release();
        return r;
    }

    Result read(uint16_t op, const std::vector<uint32_t>& params, void (*progress)(uint64_t, uint64_t) = nullptr) {
        Result r;
        IPortableDeviceValues* in = command(WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITH_DATA_TO_READ, op, params);
        IPortableDeviceValues* out = nullptr;
        r.hr = send(in, &out);
        in->Release();
        if (FAILED(r.hr)) { if (out) out->Release(); return r; }
        LPWSTR ctx = nullptr;
        ULONGLONG total = 0;
        out->GetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, &ctx);
        out->GetUnsignedLargeIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_TOTAL_DATA_SIZE, &total);
        out->Release();
        r.data.reserve((size_t)total);
        const ULONG chunk = 1024 * 1024;
        std::vector<uint8_t> buf(chunk);
        while (r.data.size() < total) {
            IPortableDeviceValues* rin = newValues();
            simpleCmd(WPD_COMMAND_MTP_EXT_READ_DATA, rin);
            rin->SetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, ctx);
            ULONG want = (ULONG)std::min<ULONGLONG>(chunk, total - r.data.size());
            rin->SetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_NUM_BYTES_TO_READ, want);
            rin->SetBufferValue(WPD_PROPERTY_MTP_EXT_TRANSFER_DATA, buf.data(), want);
            IPortableDeviceValues* rout = nullptr;
            HRESULT h = send(rin, &rout);
            rin->Release();
            if (FAILED(h)) { r.hr = h; if (rout) rout->Release(); break; }
            BYTE* got = nullptr;
            DWORD gotLen = 0;
            rout->GetBufferValue(WPD_PROPERTY_MTP_EXT_TRANSFER_DATA, &got, &gotLen);
            ULONG n = 0;
            rout->GetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_NUM_BYTES_READ, &n);
            if (got) {
                DWORD use = std::min<DWORD>(gotLen, n ? n : gotLen);
                r.data.insert(r.data.end(), got, got + use);
                CoTaskMemFree(got);
            }
            rout->Release();
            if (progress) progress(r.data.size(), total);
            if (n == 0 && gotLen == 0) break;
        }
        HRESULT h2 = S_OK;
        r.code = endTransfer(ctx, &h2);
        if (SUCCEEDED(r.hr) && FAILED(h2)) r.hr = h2;
        CoTaskMemFree(ctx);
        return r;
    }

    Result write(uint16_t op, const std::vector<uint32_t>& params, const uint8_t* data, size_t size,
                 void (*progress)(uint64_t, uint64_t) = nullptr) {
        Result r;
        IPortableDeviceValues* in = command(WPD_COMMAND_MTP_EXT_EXECUTE_COMMAND_WITH_DATA_TO_WRITE, op, params);
        in->SetUnsignedLargeIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_TOTAL_DATA_SIZE, size);
        IPortableDeviceValues* out = nullptr;
        r.hr = send(in, &out);
        in->Release();
        if (FAILED(r.hr)) { if (out) out->Release(); return r; }
        LPWSTR ctx = nullptr;
        out->GetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, &ctx);
        out->Release();
        const size_t chunk = 1024 * 1024;
        size_t done = 0;
        while (done < size) {
            ULONG n = (ULONG)std::min<size_t>(chunk, size - done);
            IPortableDeviceValues* win = newValues();
            simpleCmd(WPD_COMMAND_MTP_EXT_WRITE_DATA, win);
            win->SetStringValue(WPD_PROPERTY_MTP_EXT_TRANSFER_CONTEXT, ctx);
            win->SetUnsignedIntegerValue(WPD_PROPERTY_MTP_EXT_TRANSFER_NUM_BYTES_TO_WRITE, n);
            win->SetBufferValue(WPD_PROPERTY_MTP_EXT_TRANSFER_DATA, const_cast<BYTE*>(data + done), n);
            IPortableDeviceValues* wout = nullptr;
            HRESULT h = send(win, &wout);
            win->Release();
            if (wout) wout->Release();
            if (FAILED(h)) { r.hr = h; break; }
            done += n;
            if (progress) progress(done, size);
        }
        HRESULT h2 = S_OK;
        r.code = endTransfer(ctx, &h2);
        if (SUCCEEDED(r.hr) && FAILED(h2)) r.hr = h2;
        CoTaskMemFree(ctx);
        return r;
    }
};

// ---------------------------------------------------------------- helpers

static std::string ptpString(const std::vector<uint8_t>& d, size_t& off) {
    if (off >= d.size()) return {};
    uint8_t n = d[off++];
    std::wstring w;
    for (int i = 0; i < n && off + 1 < d.size(); i++, off += 2) {
        wchar_t c = (wchar_t)(d[off] | (d[off + 1] << 8));
        if (c) w.push_back(c);
    }
    return narrow(w);
}

static std::vector<uint16_t> ptpU16Array(const std::vector<uint8_t>& d, size_t& off) {
    std::vector<uint16_t> a;
    if (off + 4 > d.size()) return a;
    uint32_t n = d[off] | (d[off + 1] << 8) | (d[off + 2] << 16) | ((uint32_t)d[off + 3] << 24);
    off += 4;
    for (uint32_t i = 0; i < n && off + 1 < d.size(); i++, off += 2) a.push_back((uint16_t)(d[off] | (d[off + 1] << 8)));
    return a;
}

static bool has(const std::vector<uint16_t>& a, uint16_t v) {
    for (auto x : a) if (x == v) return true;
    return false;
}

static void bar(uint64_t done, uint64_t total) {
    static int last = -1;
    int pct = total ? (int)(done * 100 / total) : 100;
    if (pct != last && (pct % 10 == 0 || pct == 100)) {
        printf("    %d%%\n", pct);
        fflush(stdout);
        last = pct;
    }
    if (pct == 100) last = -1;
}

static std::vector<uint8_t> ptpStr(const wchar_t* s) {
    std::vector<uint8_t> b;
    size_t n = wcslen(s) + 1;
    b.push_back((uint8_t)n);
    for (size_t i = 0; i < n; i++) { b.push_back((uint8_t)(s[i] & 0xFF)); b.push_back((uint8_t)(s[i] >> 8)); }
    return b;
}

static void put16(std::vector<uint8_t>& b, uint16_t v) { b.push_back(v & 0xFF); b.push_back(v >> 8); }
static void put32(std::vector<uint8_t>& b, uint32_t v) { for (int i = 0; i < 4; i++) b.push_back((v >> (8 * i)) & 0xFF); }

static std::wstring exeDir() {
    wchar_t p[MAX_PATH];
    GetModuleFileNameW(nullptr, p, MAX_PATH);
    std::wstring s = p;
    return s.substr(0, s.find_last_of(L"\\/") + 1);
}

static bool readFile(const std::wstring& path, std::vector<uint8_t>& out) {
    FILE* f = _wfopen(path.c_str(), L"rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(n);
    size_t got = fread(out.data(), 1, n, f);
    fclose(f);
    return got == (size_t)n;
}

static std::wstring findRaf(int argc, wchar_t** argv) {
    for (int i = 1; i < argc; i++) {
        std::wstring a = argv[i];
        if (a.size() > 4 && _wcsicmp(a.c_str() + a.size() - 4, L".raf") == 0) return a;
    }
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW((exeDir() + L"*.raf").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return {};
    FindClose(h);
    return exeDir() + fd.cFileName;
}

// ---------------------------------------------------------------- main

static int run(int argc, wchar_t** argv) {
    say("==== 富士食堂 USB 測試 ====");
    say("相機請先設定：連線模式 → USB RAW CONV./BACKUP RESTORE，再用 USB 線接電腦。");
    say("");

    IPortableDeviceManager* mgr = nullptr;
    HRESULT h = CoCreateInstance(CLSID_PortableDeviceManager, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&mgr));
    if (FAILED(h)) { say("[失敗] 無法使用 Windows 的裝置介面（%s）", hr(h).c_str()); return 1; }
    mgr->RefreshDeviceList();
    DWORD count = 0;
    mgr->GetDevices(nullptr, &count);
    say("[1] Windows 看到 %lu 個可攜式裝置：", count);
    std::vector<LPWSTR> ids(count);
    if (count) mgr->GetDevices(ids.data(), &count);
    int pick = -1;
    for (DWORD i = 0; i < count; i++) {
        wchar_t name[256] = L"", maker[256] = L"", desc[256] = L"";
        DWORD n1 = 256, n2 = 256, n3 = 256;
        mgr->GetDeviceFriendlyName(ids[i], name, &n1);
        mgr->GetDeviceManufacturer(ids[i], maker, &n2);
        mgr->GetDeviceDescription(ids[i], desc, &n3);
        say("    - %s ／ %s ／ %s", narrow(name).c_str(), narrow(maker).c_str(), narrow(desc).c_str());
        std::wstring all = std::wstring(name) + maker + desc;
        for (auto& c : all) c = towupper(c);
        if (pick < 0 && (all.find(L"FUJI") != std::wstring::npos || all.find(L"X-T") != std::wstring::npos)) pick = (int)i;
    }
    if (pick < 0) {
        say("");
        say("[失敗] 沒找到富士相機。請確認：相機開機、USB 線是傳輸線、連線模式是 USB RAW CONV./BACKUP RESTORE。");
        say("      如果你曾用 Zadig 換過這台相機的驅動，Windows 的裝置介面就看不到它。");
        return 2;
    }
    say("    → 用這台：%s", narrow(ids[pick]).c_str());

    Ptp ptp;
    CoCreateInstance(CLSID_PortableDeviceFTM, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&ptp.dev));
    IPortableDeviceValues* client = ptp.newValues();
    client->SetStringValue(WPD_CLIENT_NAME, L"FujiShokudoProbe");
    client->SetUnsignedIntegerValue(WPD_CLIENT_MAJOR_VERSION, 1);
    client->SetUnsignedIntegerValue(WPD_CLIENT_MINOR_VERSION, 0);
    client->SetUnsignedIntegerValue(WPD_CLIENT_REVISION, 0);
    client->SetUnsignedIntegerValue(WPD_CLIENT_DESIRED_ACCESS, GENERIC_READ | GENERIC_WRITE);
    client->SetUnsignedIntegerValue(WPD_CLIENT_SHARE_MODE, FILE_SHARE_READ | FILE_SHARE_WRITE);
    h = ptp.dev->Open(ids[pick], client);
    client->Release();
    if (FAILED(h)) { say("[失敗] 打不開相機（%s）。是不是有別的程式（X RAW STUDIO、相片匯入）正在用？", hr(h).c_str()); return 3; }
    say("[2] 相機已打開");

    // ---- does Windows itself talk to it? (its own property read, no PTP from us)
    {
        IPortableDeviceContent* content = nullptr;
        IPortableDeviceProperties* pr = nullptr;
        IPortableDeviceValues* vals = nullptr;
        if (SUCCEEDED(ptp.dev->Content(&content)) && SUCCEEDED(content->Properties(&pr))) {
            HRESULT hp = pr->GetValues(WPD_DEVICE_OBJECT_ID, nullptr, &vals);
            LPWSTR fw = nullptr;
            if (SUCCEEDED(hp) && vals) vals->GetStringValue(WPD_DEVICE_FIRMWARE_VERSION, &fw);
            say("    Windows 自己讀相機資訊：%s%s%s", SUCCEEDED(hp) ? "OK" : hr(hp).c_str(),
                fw ? "，韌體 " : "", fw ? narrow(fw).c_str() : "");
            if (fw) CoTaskMemFree(fw);
        }
        if (vals) vals->Release();
        if (pr) pr->Release();
        if (content) content->Release();
    }

    // ---- device info (retry: right after plugging in, Windows keeps the camera busy for a while)
    Ptp::Result info;
    for (int attempt = 1; attempt <= 15; attempt++) {
        info = ptp.read(0x1001, {});
        if (SUCCEEDED(info.hr) && info.data.size() >= 12) break;
        say("    第 %d 次送指令：%s%s", attempt, hr(info.hr).c_str(),
            info.hr == HRESULT_FROM_WIN32(ERROR_BUSY) ? "（相機忙碌中，2 秒後再試）" : "");
        if (attempt == 1 && info.hr == HRESULT_FROM_WIN32(ERROR_BUSY))
            say("    → 如果一直忙碌：關掉 X RAW STUDIO、相片 App、正在顯示相機的檔案總管視窗，或拔掉重插。");
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
    if (FAILED(info.hr) || info.data.size() < 12) {
        say("[失敗] PTP 指令一直送不出去（%s，回應 %04X）。", hr(info.hr).c_str(), info.code);
        return 4;
    }
    size_t off = 8;  // StandardVersion(2) VendorExtensionID(4) VendorExtensionVersion(2)
    std::string ext = ptpString(info.data, off);
    off += 2;        // FunctionalMode
    auto ops = ptpU16Array(info.data, off);
    auto events = ptpU16Array(info.data, off);
    auto props = ptpU16Array(info.data, off);
    auto capFmt = ptpU16Array(info.data, off);
    auto imgFmt = ptpU16Array(info.data, off);
    std::string maker = ptpString(info.data, off);
    std::string model = ptpString(info.data, off);
    std::string ver = ptpString(info.data, off);
    say("[3] PTP 指令可以送！相機：%s %s（韌體 %s），支援 %zu 個指令、%zu 個屬性",
        maker.c_str(), model.c_str(), ver.c_str(), ops.size(), props.size());
    bool canSend = has(ops, 0x900C) && has(ops, 0x900D);
    bool canProfile = has(props, 0xD185) || has(props, 0xD183);
    say("    RAW 上傳指令（900C/900D）：%s", canSend ? "有" : "沒有");
    say("    轉檔設定屬性（D183/D185）：%s", canProfile ? "有" : "沒看到（有些機型沒列出，不一定代表不行）");

    // ---- a vendor property read: does the vendor path work at all?
    auto prof = ptp.read(0x1015, {0xD185});
    say("    讀轉檔設定 D185：%s（回應 %04X，%zu bytes）",
        SUCCEEDED(prof.hr) ? "可以" : hr(prof.hr).c_str(), prof.code, prof.data.size());

    // ---- full bake test with a RAF
    std::wstring rafPath = findRaf(argc, argv);
    if (rafPath.empty()) {
        say("");
        say("[4] 沒有找到 .RAF，所以跳過「真的烘一張」的測試。");
        say("    想測完整流程：把一個 %s 拍的 .RAF 放在這個程式旁邊（或直接拖到程式圖示上），再執行一次。", model.c_str());
        return 0;
    }
    std::vector<uint8_t> raf;
    if (!readFile(rafPath, raf) || raf.size() < 100 || memcmp(raf.data(), "FUJIFILMCCD-RAW", 15) != 0) {
        say("[4] %s 不是富士 RAW，跳過。", narrow(rafPath).c_str());
        return 0;
    }
    char rafModel[33] = {0};
    memcpy(rafModel, raf.data() + 0x1C, 32);
    say("");
    say("[4] 烘焙測試：%s（%.1f MB，%s 拍的）", narrow(rafPath).c_str(), raf.size() / 1048576.0, rafModel);

    // clear anything left from an earlier run
    for (int i = 0; i < 4; i++) {
        auto hs = ptp.read(0x1007, {0xFFFFFFFF, 0, 0});
        if (hs.data.size() < 8) break;
        uint32_t n = *(uint32_t*)hs.data.data();
        if (!n) break;
        ptp.noData(0x100B, {*(uint32_t*)(hs.data.data() + 4)});
    }

    std::vector<uint8_t> oi;
    put32(oi, 0); put16(oi, 0xF802); put16(oi, 0); put32(oi, (uint32_t)raf.size());
    put16(oi, 0); put32(oi, 0); put32(oi, 0); put32(oi, 0);
    put32(oi, 0); put32(oi, 0); put32(oi, 0); put32(oi, 0);
    put16(oi, 0); put32(oi, 0); put32(oi, 0);
    auto nm = ptpStr(L"FUP_FILE.dat");
    oi.insert(oi.end(), nm.begin(), nm.end());
    oi.push_back(0); oi.push_back(0); oi.push_back(0);
    auto t0 = std::chrono::steady_clock::now();
    auto r1 = ptp.write(0x900C, {0, 0, 0}, oi.data(), oi.size());
    say("    送檔案資訊：%s（回應 %04X）", SUCCEEDED(r1.hr) ? "OK" : hr(r1.hr).c_str(), r1.code);
    if (FAILED(r1.hr) || r1.code != 0x2001) { say("[失敗] 相機不收 RAW 資訊。"); return 5; }
    say("    傳送 RAW…");
    auto r2 = ptp.write(0x900D, {}, raf.data(), raf.size(), bar);
    double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    say("    傳送 RAW：%s（回應 %04X），%.1f 秒，%.1f MB/s", SUCCEEDED(r2.hr) ? "OK" : hr(r2.hr).c_str(), r2.code,
        secs, raf.size() / 1048576.0 / secs);
    if (FAILED(r2.hr) || r2.code != 0x2001) { say("[失敗] RAW 傳送失敗。"); return 6; }

    auto base = ptp.read(0x1015, {0xD185});
    say("    讀這張的轉檔設定：%s（%zu bytes）", SUCCEEDED(base.hr) && base.code == 0x2001 ? "OK" : "失敗", base.data.size());
    if (base.data.size() < 100) { say("[失敗] 讀不到轉檔設定。"); return 7; }

    // write it back unchanged (as shot), then start
    auto r3 = ptp.write(0x1016, {0xD185}, base.data.data(), base.data.size());
    say("    寫回轉檔設定：%s（回應 %04X）", SUCCEEDED(r3.hr) ? "OK" : hr(r3.hr).c_str(), r3.code);
    uint8_t zero[2] = {0, 0};
    auto t1 = std::chrono::steady_clock::now();
    auto r4 = ptp.write(0x1016, {0xD183}, zero, 2);
    say("    開始烘焙：%s（回應 %04X）", SUCCEEDED(r4.hr) ? "OK" : hr(r4.hr).c_str(), r4.code);
    if (FAILED(r4.hr) || r4.code != 0x2001) { say("[失敗] 相機沒有開始烘焙。"); return 8; }

    uint32_t handle = 0;
    for (int i = 0; i < 120 && !handle; i++) {
        auto hs = ptp.read(0x1007, {0xFFFFFFFF, 0, 0});
        if (hs.data.size() >= 8 && *(uint32_t*)hs.data.data() > 0) handle = *(uint32_t*)(hs.data.data() + 4);
        else std::this_thread::sleep_for(std::chrono::milliseconds(400));
    }
    double bakeSecs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
    if (!handle) { say("[失敗] 等了一分鐘相機都沒烘好。"); return 9; }
    say("    烘好了（%.1f 秒），取回中…", bakeSecs);
    auto jpg = ptp.read(0x1009, {handle}, bar);
    ptp.noData(0x100B, {handle});
    if (FAILED(jpg.hr) || jpg.data.size() < 1000) { say("[失敗] 取回成品失敗（%s）。", hr(jpg.hr).c_str()); return 10; }
    std::wstring outPath = exeDir() + L"烘焙測試.jpg";
    FILE* f = _wfopen(outPath.c_str(), L"wb");
    if (f) { fwrite(jpg.data.data(), 1, jpg.data.size(), f); fclose(f); }
    say("    成品 %.1f MB，存在：%s", jpg.data.size() / 1048576.0, narrow(outPath).c_str());
    say("");
    say("[成功] 不用換驅動，整套烘焙在 Windows 上走得通！");
    return 0;
}

int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    g_log = _wfopen((exeDir() + L"富士食堂USB測試結果.txt").c_str(), L"wb");
    if (g_log) fputs("\xEF\xBB\xBF", g_log);
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    int rc = run(argc, argv);
    say("");
    say("結果也存在旁邊的「富士食堂USB測試結果.txt」，把它傳給 Claude 就好。按 Enter 結束。");
    if (g_log) fclose(g_log);
    getchar();
    return rc;
}
