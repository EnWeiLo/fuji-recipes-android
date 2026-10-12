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

## 拍立得

「拍立得」分頁是原生重寫的拍立得相框工具（原網頁版：polaroid-tool），功能一樣：

- 一次最多 20 張，點縮圖切換，可套用另一張的設定
- 五種邊框（白／黑／取色／復古／底片）、七種比例（原圖、Mini、方框、Wide、橫幅、IG 貼文、IG 限動）
- 自動讀取 EXIF：相機、鏡頭、快門／光圈／ISO、日期；也可手動輸入或關閉；已確認的機型顯示官方字標
- 備註、日期戳（可上下拖曳）、12 種字型、字體大小與顏色
- 拖曳預覽調整裁切、雙指縮放；點照片可更換這一張
- 分享、儲存到相簿（Pictures/拍立得）、全部打包成 ZIP（下載資料夾）

字型：粉圓、辰宇落雁、源流明體已精簡後放在 `app/src/main/assets/polaroid/fonts`；
Caveat、Share Tech Mono、Zen Maru Gothic、Kiwi Maru、Yuji Syuku 在編譯時由
`scripts/fetch_polaroid_fonts.py` 從 google/fonts 下載並精簡（抓不到時改用系統字型）。

另有一個 `Render check` 工作流程：在模擬器上用真正的渲染程式畫出各種卡片與畫面截圖，
放到 `render-check` 分支，方便檢查。

## 效果需求

- 液態玻璃折射：Android 13+（API 33）
- 玻璃模糊：Android 12+（API 31）
- 更舊的系統會自動改用不透明面板，功能不受影響

## 簽名金鑰

`app/fuji-recipes.jks` 是公開在倉庫裡的個人用金鑰，只為了讓更新能覆蓋安裝。
不要拿它簽署其他 App，也不要視為安全的簽名。
