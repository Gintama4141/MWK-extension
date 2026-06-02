# MWK-extension AGENTS.md

## Goal
Maintain and optimize the MWK-extension CloudStream3 plugin repo for Indonesian-subtitled streaming; debug/fix Kuronime streaming error; clean up repo.

## Constraints & Preferences
- Must support subtitle Indonesia (`lang = "id"`) for all providers.
- Build/deploy via GitHub Actions to branch `builds`.
- Must use CloudStream API conventions — no Jackson `ObjectMapper`.
- Use `tryParseJson` instead of `.parsedSafe()` (latter calls internal `parseJson` method missing in user's CloudStream version).
- Bump provider version on every change.
- Git repo root: `C:\Users\Darul Izzah Yoso\Downloads\Project\MWK-extension` (newly initialized).

## Status: ✅ Complete
- Diagnosed `NoSuchMethodError: No virtual method parseJson(...)` — `.parsedSafe<Servers>()` not compatible. Replaced with `.text` + `tryParseJson<Servers>()`.
- Analyzed `winie.2.min.js` — AES key is `"3&!Z0M,;dZWVIZ=="`.
- Updated KuronimeProvider AES key + added `src_sd` handling + `Servers` data class.
- Replaced `AesHelper.cryptoAESHandler` with manual **CryptoJS EVP_BytesToKey + AES/CBC/PKCS5Padding** decryption. Fixed `base64Decode` → `android.util.Base64.decode()`.
- Bumped versions: Kuronime 5, Otakudesu 5, Anichin 3, others 2.
- Deleted 19 unwanted providers. Kept 10: Anichin, Cinemax21, Donghub, Kawanfilm, Kuronime, Moviebox, NgeFilm21, Nomat, Otakudesu, PencuriMovie.
- Updated `repo.json` → Gintama4141/MWK-extension using `builds` branch.
- Rewrote README.md.
- Fixed git repo: re-initialized at `MWK-extension/` (was incorrectly at home dir). Pushed to `origin/master`.
- Remote already had all fixes (AES, EVP_BytesToKey, version bumps) via prior CI pushes.
- Final push: `004909a` — cleanup (delete Dutamovie, KlikxxiProvider; add Anichin extractors).

## Key Files
- `KuronimeProvider/src/main/kotlin/com/kuronime/KuronimeProvider.kt` — AES key, `decryptCryptoJS()`, `tryParseJson`, `src_sd` support.
- `repo.json` — plugin repo config.
- `README.md` — provider list.

## Remaining Notes
- Kuronime streaming fix (manual EVP_BytesToKey decrypt) is deployed but untested by user.
- User needs to download CI build from GitHub Actions and test Liar Game E1 streaming.
- Otakudesu v5 confirmed working.
- If Kuronime still fails, next steps: add debug logging, test `src_sd`/`mirror` paths, or try `animeku.org/player2.php?id=<id>` as loadable URL.
