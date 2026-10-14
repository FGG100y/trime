<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 同文 Android 輸入法平臺

![build](https://github.com/osfans/trime/actions/workflows/commit-ci.yml/badge.svg?branch=develop)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![GitHub release](https://img.shields.io/github/release/osfans/trime.svg)](https://github.com/osfans/trime/releases)
[![F-Droid release](https://img.shields.io/f-droid/v/com.osfans.trime.svg)](https://f-droid.org/packages/com.osfans.trime)
[![Latest build](https://img.shields.io/github/last-commit/osfans/trime.svg)](http://osfans.github.io/trime/)

[English](README.md) | [简体中文](README_sc.md) | 繁體中文

## 關於

源於開源的[注音倉頡輸入法]前端，基於著名的 [RIME] 輸入法框架，使用 JNI 的 C 語言和 Android 的 Java/Kotlin 語言書寫，旨在保護漢語各地方言母語，音碼、形碼通用的輸入法平臺。

[查看文檔](https://github.com/osfans/trime/wiki)

## 下載

- 穩定版 <br>
  [<img alt='Get it on F-Droid' src='https://fdroid.gitlab.io/artwork/badge/get-it-on.png' height='80px'/>](https://f-droid.org/packages/com.osfans.trime)
  [<img alt='Google Play 立即下載' src='https://play.google.com/intl/en_us/badges/images/generic/zh-tw_badge_web_generic.png' height='80px'/>](https://play.google.com/store/apps/details?id=com.osfans.trime)

- 每夜版 [點選下載](https://github.com/osfans/trime/releases/tag/nightly)

- 測試版 [點選下載](https://github.com/osfans/trime/actions)

- 配置文件 [rimerc](https://github.com/Bambooin/rimerc)

## 本機語音輸入（本分支特有）

> 僅存在於 `feature/on-device-voice-input` 分支的實驗性工作，尚未併入上游 Trime。
> 設計與實作說明：[`doc/voice-input.md`](doc/voice-input.md)。

本分支加入了可選的、**完全在本機執行**的語音輸入：長按空白鍵說話，停頓一下，剛才那句就
插到游標處。音訊不離開裝置，App 依然不申請 `INTERNET` 權限。

與其自己照著步驟編譯、匯入模型，不如把整件事交給編碼 Agent（Claude Code、Codex、pi……）。
把下面這段原樣複製給它：

```text
請在手機上幫我裝好帶本機語音輸入的 Trime：

1. clone https://github.com/FGG100y/trime ，切到 feature/on-device-voice-input 分支
2. 先跑 script/fetch-sherpa-onnx.sh，再用 BUILD_ABI=arm64-v8a 編 debug 包
   （別編 armeabi-v7a，目前會失敗）
3. adb install -r 裝 debug 包（com.osfans.trime.debug）
4. 可選：幫我把語音模型解壓縮到 /sdcard/Download 下
5. 最後把“只有我能在手機上做”的步驟列成清單給我

約束：adb 只允許操作這一個 App（安裝 / 啟動 / 看日誌 / 截圖）。不要替我點其它 App 或
系統設定，也不要在裝置上代我操作——把步驟寫下來，我自己執行。
```

### Agent 能自己做完的

- 拉取預編譯原生函式庫並建置：`script/fetch-sherpa-onnx.sh`，然後
  `BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug`（約 240 KB 的 API jar 已提交，
  乾淨檢出即可編譯；約 30 MB 的 `.so` 沒有提交）。
- 用 adb 安裝，並在你試用的同時抓這個輸入法自己的日誌。
- 下載並解壓縮模型到共享儲存空間，讓匯入這一步更省事。

### 只能你自己做的（讓 Agent 把這份清單交給你）

| 步驟 | 在哪裡 |
| --- | --- |
| 啟用並選中“Trime（debug）” | 系統設定 → 語言和輸入法（輸入法只能由使用者自行啟用） |
| 授權麥克風 | 第一次長按空白鍵時跳出的對話方塊 |
| 匯入模型（在系統選擇器裡選模型**資料夾**） | Trime 設定 → *Local voice input* → Import model |
| 把 *Long press space* 設為 *Local voice input* | Trime 設定 → *Local voice input*（“一般”頁也有） |
| 部署一次 Rime，才會產生 `t9.prism.bin` 等 | Trime 主介面 →“**部署**”（僅當你同時使用配套的左手九宮格配置，見下） |

推薦模型：**SenseVoiceSmall**
（[`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2)，
約 230 MB，中文效果好，自帶標點）。其它可用的模型結構、儲存/記憶體基線、以及 release
命名裡的坑，見 [`doc/voice-input.md`](doc/voice-input.md)。

### 鍵盤那一側

這個建置配合使用的左手九宮格佈局（`t9` / `t9_en`，長按空白鍵綁定語音）在另一個倉庫
**trime-config**（`git@github.com:FGG100y/trime-config.git`，目前是私有倉庫）；它的
README 裡有 `script/push.sh` 和“部署”那一步。用官方原版 APK 的話只有“系統語音輸入法”
這個選項，沒有本機辨識引擎。

## 沿革

TRIME 是 Tongwen RIME 或是 ThaeRvInputMEthod 的縮寫:

- 最初，輸入法是寫給[泰如拼音](http://taerv.nguyoeh.com/ime/)（tae5 rv2）的，中文名為“泰如輸入法”;
- 然後，添加了吳語等方言碼錶，做成了一個輸入法平臺，更名為“漢字方言輸入法”;
- 後來，相容了五筆、兩筆等形碼，在太空衛士、徵羽的建議下，更名為“[同文輸入法平臺 2.x](https://github.com/osfans/trime-legacy)”。寓意音碼形碼同臺，方言官話同文。
- 之後，藉助 JNI 技術，享受了 [librime](https://github.com/rime/librime) 的成果，升級為“同文輸入法平臺 3.x”，簡稱“同文輸入法”。

現在歡迎你前來[貢獻](CONTRIBUTING.md) ～！

## 開發入門

### 準備

#### 開發環境要求

- Android SDK 和 Android NDK
  - 如果還不熟悉 Android 開發，建議安裝 [Android Studio](https://developer.android.com/studio)，它會自動安裝並配置 Android 開發環境。

- JDK（OpenJDK）17
- Python 3 (用於給 OpenCC 生成詞典文字檔案)

#### Windows 上的前提條件

當前構建配置會使構建過程中建立符號連結，開發者需要：

- 啟用[開發者模式](https://learn.microsoft.com/zh-cn/windows/apps/get-started/enable-your-device-for-development) 以在無管理員許可權的情況下建立符號連結。

- 啟用 `git` 的符號連結支援：

  ```powershell
  git config --global core.symlinks true
  ```

如果無法或者不想啟用上述設定也沒關係。構建系統會自動在符號連結建立失敗時使用複製代替。

### 構建

#### 1. 克隆此專案並拉取所有子模組。

```sh
git clone git@github.com:osfans/trime.git
git submodule update --init --recursive
# 可以使用部分克隆節省時間
git submodule update --init --recursive --filter=blob:none
```

#### 2. 編譯除錯版本:

```sh
# On Linux or macOS
make debug

# On Windows
.\gradlew assembleDebug
```

#### 3. 編譯正式版本：

請建立 `keystore.properties` 檔案，包含以下內容，註明[簽名信息](https://developer.android.com/studio/publish/app-signing.html)：

```gradle.properties
storePassword=myStorePassword
keyPassword=mykeyPassword
keyAlias=myKeyAlias
storeFile=myStoreFileLocation
```

然後執行：

```sh
# On Linux or macOS
make release

# On Windows
.\gradlew assembleRelease
```

### 故障排除

```
Target "boost_log_setup" links to target "Boost::coroutine" but the target was not found.
```

在 Linux 或 macOS 上執行 `make clean`，Windows 上執行 `.\gradlew clean`。

其他問題:

1. 首先嚐試 `make clean`
2. 確保你的倉庫與最新版本一致。如果你修改了一個或更多的子模組，請確保它們與當前倉庫版本相容。
3. 如果問題依然存在（不太可能）, 嘗試進行一次新的克隆。
4. 檢查是否有 PR/issue 與你的問題相關。 如果有的話，嘗試他們的解決方案。
5. 如果以上方法都不工作，你可以提一個 issue 來尋求幫助(可選)。

## 鳴謝

- 開發：[osfans](https://github.com/osfans)
- 貢獻：[boboIqiqi](https://github.com/boboIqiqi)、[Bambooin](https://github.com/Bambooin)、[senchi96](https://github.com/senchi96)、[heiher](https://github.com/heiher)、[abay](https://github.com/a342191555)、[iovxw](https://github.com/iovxw)、[huyz-git](https://github.com/huyz-git)、[tumuyan](https://github.com/tumuyan)、[WhiredPlanck](https://github.com/WhiredPlanck)、[nopdan](https://github.com/nopdan)......
- [維基](https://github.com/osfans/trime/wiki)：[xiaoqun2016](https://github.com/xiaoqun2016)、[boboIqiqi](https://github.com/boboIqiqi)......
- 翻譯：天真可愛的滿滿（繁體中文）、點解（英文）......
- 鍵盤：天真可愛的滿滿、皛筱曉小笨魚、吳琛 11、熊貓阿 Bo、默默ㄇㄛ ˋ......
- 捐贈：[Releases](https://github.com/osfans/trime/releases) 中的“打賞”實時更新
- 社群：在 [Issues](https://github.com/osfans/trime/issues)、[QQ 群 (811142286)](https://jq.qq.com/?_wv=1027&k=AXdR80HN)、[QQ 群 (224230445)](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=pg_q7UVumWYLq1Rk8kIAqkK1xGt64VnX&authKey=04m9l7OBO5H5vgrEL8IbpsmtnptWM60xy%2FUwYCfyvw9VcRhe8zRzAS1ezoemZdFr&noverify=0&group_code=224230445)、[貼吧](http://tieba.baidu.com/f?kw=rime)、[Google Play](https://play.google.com/store/apps/details?id=com.osfans.trime)、[Telegram](https://t.me/trime_dev) 中反饋意見的網友
- 專案：[RIME]、[OpenCC]、[注音倉頡輸入法]等開源專案

## 第三方庫

- [Boost C++ Libraries](https://www.boost.org/) (Boost Software License)
- [darts-clone](https://github.com/s-yata/darts-clone) (New BSD License)
- [LevelDB](https://github.com/google/leveldb) (New BSD License)
- [libiconv](https://www.gnu.org/software/libiconv/) (LGPL License)
- [marisa-trie](https://github.com/s-yata/marisa-trie) (BSD License)
- [glog](https://github.com/google/glog) (New BSD License)
- [OpenCC](https://github.com/BYVoid/OpenCC) (Apache License 2.0)
- [RIME](https://rime.im) (BSD License)
- [snappy](https://github.com/google/snappy)(BSD License)
- [utfcpp](https://github.com/nemtrif/utfcpp) (Boost Software License)
- [yaml-cpp](https://github.com/jbeder/yaml-cpp) (MIT License)
- [注音倉頡輸入法](https://code.google.com/p/android-traditional-chinese-ime/) (Apache License 2.0)

[注音倉頡輸入法]: https://code.google.com/p/android-traditional-chinese-ime/
[RIME]: http://rime.im
[OpenCC]: https://github.com/BYVoid/OpenCC
