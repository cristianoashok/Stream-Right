# 🎬 Stream (Android Media Streaming Engine)

A production-grade, high-performance Android media streaming application engineered with modern Android architecture, custom multi-provider resolution pipelines, and an adaptive video player.

---

## 🚀 Key Features

* **Declarative Multi-Provider Engine:** Dynamic provider registry supporting automated server failover, latency scoring, and runtime reliability tracking.
* **Headless WebView Resolution Engine:** Automated sub-iframe resolution, cross-candidate generation isolation, and header-based origin/referer preservation to bypass modern CDN blocks and bot-checks.
* **Advanced Media Playback:** Powered by **AndroidX Media3 (ExoPlayer)** supporting adaptive HLS/DASH streaming, multi-track subtitle selection, dynamic playback controls, and automated intro/credit segment skipping.
* **Robust ID Bridging:** Seamless metadata synchronization mapping **TMDB IDs** to **AniList / MAL IDs** for unified movie, TV series, and anime playback.
* **Modern Android Stack:** Built 100% with **Jetpack Compose**, Kotlin Coroutines, StateFlow, and Clean Architecture principles.

---

## 🛠️ Architecture & Tech Stack

* **UI:** Jetpack Compose, Material 3, ConstraintLayout
* **Architecture:** MVVM / Unidirectional Data Flow (UDF) with Coroutines & Flow
* **Media & Networking:** ExoPlayer (Media3), Retrofit 2, OkHttp 3, Gson
* **Resolution Pipeline:** Headless Chromium WebViews with custom JavaScript sniffer hooks for automated stream harvesting

---

## ⚙️ Getting Started & Setup

1. **Clone the repository:**
   ```bash
   git clone [https://github.com/cristianoashok/Stream-Right.git](https://github.com/cristianoashok/Stream-Right.git)
