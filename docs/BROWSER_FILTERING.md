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

## Browser compatibility fixes

The WebView respects mobile viewport metadata without automatic overview zoom-out when overlay/player content overflows. Pinch zoom remains available without floating zoom controls. HTML Fullscreen requests now attach the custom view to an opaque full-window container; Back, exit and tab disposal remove it and restore system bars/window flags.

Gesture-originated popup destinations still pass subscription popup rules, but while filtering is enabled an unmatched cross-site popup is also silently refused; same-site destinations remain available. This keeps unknown ad redirects from taking over the active tab. With filtering disabled, gesture-originated popups can navigate normally. No SSL or storage protection was relaxed.

The browser keeps the installed WebView Chromium version while removing the Android embedded-WebView user-agent markers (`; wv` and `Version/4.0`) that cause some sites to serve an incompatible embedded-app layout. Video quality clicks are resolved through the composed event path so controls inside Web Components/Shadow DOM can reach the existing native download confirmation.

Media capture recognizes HLS served with an image-looking URL/content type only when the document explicitly declares that exact URL as HLS in a video source. Ordinary images are not scanned as playlists. In a Vidstack `app-player-download` control, a trusted click on a resolution routes the declared HLS rendition to the existing native confirmation dialog, rather than remuxing the entire file in JavaScript into an unsupported blob. It prefers variants already observed in the page's session and otherwise inspects the declared master with session headers. A missing resolution never silently selects a different one. The adapter is framework-specific, not a universal blob-file downloader; normal site HTTP downloads retain the existing listener.

Manual Node regression check (no emulator/JVM): `node --test android/app/src/test/js/browser-media-capture.test.cjs`. These fixtures exercise the actual injected script with a bounded fake DOM; they do not prove Android rendering or a real CDN transfer. Live inspection of AnimeFire confirmed its CDK/Popover overlays, Vidstack controls, typed image-URL HLS source and JS/blob remux download. The site's CDN blocked this cloud environment, so a complete live transfer and the reported mobile layout require confirmation on the user's device.
