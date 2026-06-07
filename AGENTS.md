# MWK-extension AGENTS.md

## Goal
Maintain and optimize the MWK-extension CloudStream3 plugin repo; fix `NoSuchMethodError` crashes from incompatible Jackson/`parseJson` calls; keep all 16 providers working with Indonesian subtitles where available.

## Constraints & Preferences
- Build/deploy via GitHub Actions to branch `builds`.
- Use `tryParseJson` instead of `.parsedSafe()` / bare `parseJson` / `ObjectMapper` / `jacksonObjectMapper` / `Gson().fromJson` (latter calls internal `parseJson` method missing in some CloudStream versions).
- Bump provider version on every change.
- Author format: `listOf("OriginalAuthor", "MWK")` for forked providers; `listOf("MWK")` only when origin unknown.
- `lang` left as-is per provider — not forced to "id".
- `AesHelper` is allowed — used for AES content decryption, not JSON parsing.

## Active Providers (16)
Anichin, Cinemax21, Donghub, Donghuastream, Dutamovie, Kawanfilm, Kisskh, Kuronime, Moviebox, NgeFilm21, Nomat, OneTouchTV, Otakudesu, PencuriMovie, TorraStream, plus Anichin extractors.

## Status: ✅ All providers clean
- All 16 providers fixed from `NoSuchMethodError: No virtual method parseJson(...)`.
- Replaced `.parsedSafe<>()` / `parseJson<>` / `ObjectMapper` / `jacksonObjectMapper` / `Gson().fromJson` with `tryParseJson` + `.text`.
- Fixed TorraStream: 6× `100L` timeouts → `30_000L`; magnet tracker cache; `toString()` → `.text` in 3× ani.zip calls + Animetosho; `Gson()` → `tryParseJson`; reversed `getQuality` 360p/480p bug.
- Replaced deprecated `SubtitleFile(` constructor with `newSubtitleFile(` in Donghub & Kisskh.
- AES key + `Servers` data class + `decryptCryptoJS()` (EVP_BytesToKey + AES/CBC/PKCS5Padding) in Kuronime for winie.2.min.js cipher.
- Build/deploy via GitHub Actions → `builds` branch.

## Recent Pushes
- `f97f8e4` Kisskh v5: `newSubtitleFile` migration
- `ae6bfcb` Donghub v4: `newSubtitleFile` migration
- `52f0c09` Kawanfilm v4: removed `jacksonObjectMapper`
- `f705839` Moviebox v5: removed `parseJson` import
- `ef9d8d6` Moviebox v4: `tryParseJson` migration
- `1c03403` TorraStream v85: tracker cache + getQuality fix
- `117ffd3` Dutamovie v4: `tryParseJson` migration
- `9ef627c` Donghuastream v2: `tryParseJson` migration
- `52b03c9` Kuronime v9: `tryParseJson` migration

## Key Files
- `KuronimeProvider/src/main/kotlin/com/kuronime/KuronimeProvider.kt` — AES key `"3&!Z0M,;dZWVIZ=="`, `decryptCryptoJS()`, `tryParseJson`, `src_sd` support.
- `TorraStreamProvider/src/main/kotlin/com/torrastream/` — `TorraStreamProvider.kt` (v85), magnet tracker cache, `getQuality` fix.
- `MovieboxProvider/src/main/kotlin/com/moviebox/MovieboxProvider.kt` (v5) — `tryParseJson<LoadData>()` + 7× `tryParseJson` migrations.
- `KawanfilmProvider/src/main/kotlin/com/kawanfilm/KawanfilmProvider.kt` (v4) — `jacksonObjectMapper` removed, `tryParseJson` for 2 call sites.
- `repo.json` — plugin repo config pointing to `builds` branch.
- `README.md` — provider list.
- `build.gradle.kts` — root dep list (Jackson + Gson still bundled for `@JsonProperty`/`@SerializedName` annotations only).

## Remaining Notes
- Otakudesu v5+ confirmed working by user.
- Kuronime v9 AES streaming fix (manual EVP_BytesToKey decrypt) deployed but untested by user — user should download CI build and test Liar Game E1 streaming.
- If Kuronime still fails, next steps: add debug logging, test `src_sd`/`mirror` paths, or try `animeku.org/player2.php?id=<id>` as loadable URL.
