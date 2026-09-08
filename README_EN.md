[简体中文](README.md) | **English**

# Daily Ledger · 记账日报

> A fully-offline Android auto expense tracker — a quick-entry dialog pops up the moment you pay, and your data never leaves the phone.
> Retro newspaper UI · Zero 3rd-party runtime deps · Zero INTERNET permission

---

## ✨ What is this

An Android expense app that **automatically pops up a quick-entry dialog the moment you pay**. WeChat/Alipay payments, transfers, red packets, or platform-direct bank deductions (Meituan/Douyin etc.) all trigger the dialog with the amount and payment time pre-filled — logging an expense takes 2 seconds.

**Privacy by design**: the manifest declares **no INTERNET permission** — every record is physically unable to leave your device.

## 📰 Highlights

- **Auto popup on payment**: three channels (notification listener + bank debit SMS + accessibility for WeChat transfers/red packets) detect your payment and pop up the ledger dialog instantly
- **Consecutive payment queue**: pay multiple times in a row and each entry is kept — confirm them one by one later, nothing lost
- **Retro newspaper UI**: masthead, letterpress category chips, seal-red accents, hatched bar charts — bookkeeping with the ritual of reading a newspaper
- **Three pages**: Ledger (records + category chart) / Stats (yearly monthly spend) / Mine (permission manager + data export)
- **Custom categories, swipe-to-delete button, year-month direct picker, editable record time**
- **CSV export**: one tap to export all records and trigger logs to your Downloads folder

## 🔐 Permissions (all optional, all local)

| Permission | Purpose |
|---|---|
| Notification listener | Core: recognizes payment notifications from WeChat/Alipay/Meituan/Douyin etc. |
| Overlay / Display over other apps | Lets the dialog pop up above other apps (Xiaomi requires a separate toggle) |
| SMS (optional) | Fallback: recognizes bank debit SMS (platform-direct payments) |
| Accessibility (optional) | Recognizes WeChat friend transfers/red packets (WeChat sends no system notification for these; only WeChat windows are monitored) |

> This app declares **no INTERNET permission**. No account, no cloud, no ads, no analytics.

## 🛠 Build

```
Android Studio Ladybug+ / Gradle 8.13 · minSdk 24 · targetSdk 36 · Java
git clone https://github.com/KlingNaA/daily-ledger.git
```
Open with Android Studio and run `assembleDebug`. No keys or signing setup required.

## 🤖 Statement

Parts of this project were built with Vibe Coding.

## 📄 License

Apache License 2.0
