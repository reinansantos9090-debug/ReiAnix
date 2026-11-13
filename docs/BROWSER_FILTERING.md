# Browser filtering and tabs

The browser uses a dynamically linked native module derived from Edsuns/AdblockAndroid's Brave/ad-block client. It is built from the source in `android/browser-filter`, not an opaque downloaded binary. Android minimum 24 and the application identity remain unchanged. Release arm64 libraries use 16 KiB page alignment. This adds native code and packaged filter data to APK size.

Balanced uses EasyList, AdGuard Mobile Ads and EasyList Portuguese. Advanced also uses EasyPrivacy and AdGuard Annoyances, extended cosmetics and supported AdGuard scriptlets. Disabled bypasses filtering. Popup rejections are silent; there is no per-block toast or confirmation dialog.

Original list URLs, snapshot hashes and source line counts are in `android/app/src/main/assets/browser-filter/sources.json`. Snapshots ship gzip-compressed and load before first protected navigation. Lists update at most once per day while the browser is in use; Settings also offers manual refresh. Downloads go only to the fixed public list providers, without page headers, URLs or cookies. HTML responses, oversized data and invalid filter sets are rejected. Atomic writes and the last-good cache protect offline operation. Filter state is independent of the library SQLite data; user mode remains in the existing settings repository.

The engine supports common ABP network rules, domain/type/party restrictions, exceptions, popup matching, cosmetic CSS, extended selectors and the scriptlet names supplied by the included library. It is not Opera's proprietary engine or full modern uBlock Origin: unsupported list modifiers are skipped; modern scriptlets, service-worker requests, and other WebView limitations can leave ads visible. No 100% blocking or site compatibility guarantee is made. Sites may require Disabled mode. The SDK snapshot is older than today's filter syntax; retain this limitation in coverage claims.

Tab previews are small RAM-only bitmaps. Inactive tabs save WebView history/scroll state and release their live WebView; returning can reload content. Rotation restores the tab URLs and selection, not private page DOM or screenshots. Each active tab creates its own media-discovery state; existing download engines, artwork and player code are unchanged. Tabs can be opened, selected, closed and returned to the local ReiAnix Browser Home.

## Provenance and licenses

The modified native SDK source and licenses are in `android/browser-filter/`; see UPSTREAM.md. ExtendedCss: https://github.com/AdguardTeam/ExtendedCss/tree/v1.3.10 . Scriptlets: https://github.com/AdguardTeam/Scriptlets/tree/v1.4.0 ; corresponding scriptlet source is included in vendor-js. EasyList/EasyPrivacy: https://easylist.to/pages/licence.html . EasyList Portuguese: https://github.com/easylist/easylistportuguese . AdGuard lists: https://github.com/AdguardTeam/AdguardFilters . Original copyrights and licenses remain applicable to these independently distributed components/data.

## Verification

Native contract source: `android/browser-filter/src/test/cpp/filter_contract_test.cc`. It can be manually compiled with the vendored non-JNI C++ sources using a host C++17 compiler. Optional first argument: a directory containing the decompressed five list text files. No emulator or CI workflow was added. Kotlin/JVM tests remain reserved for manual execution under AGENTS.md; existing automatic repository checks have not been changed.
