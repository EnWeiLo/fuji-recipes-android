# 富士食譜（Android）

把富士底片模擬食譜存在手機裡，並透過 USB（OTG）直接寫進相機的 C1–C7。
原生 Android App（Kotlin + Jetpack Compose），介面使用液態玻璃效果。

## 下載

每次推送到 `main`，GitHub Actions 會自動編譯並更新這個連結：

`https://github.com/<帳號>/<倉庫>/releases/latest/download/fuji-recipes.apk`

也可以到 Actions 頁面下載每次編譯的 artifact。

## 安裝

1. 用手機瀏覽器開上面的連結下載 APK
2. 允許「安裝不明來源應用程式」
3. 之後的新版本直接覆蓋安裝，食譜資料會保留（所有版本使用同一把簽名金鑰）

## 連接相機

1. 相機 USB 模式設成 `USB RAW CONV./BACKUP RESTORE`（或 Tether）
2. 用 OTG 線連接手機與相機
3. 打開 App →「相機」→ 連接相機，同意 USB 權限
4. 選食譜與槽位（C1–C7）→ 寫入

連線過程會記錄在「相機」頁底部的「連線紀錄」，遇到問題請把紀錄貼出來。

## 效果需求

- 液態玻璃折射：Android 13+（API 33）
- 玻璃模糊：Android 12+（API 31）
- 更舊的系統會自動改用不透明面板，功能不受影響

## 簽名金鑰

`app/fuji-recipes.jks` 是公開在倉庫裡的個人用金鑰，只為了讓更新能覆蓋安裝。
不要拿它簽署其他 App，也不要視為安全的簽名。
