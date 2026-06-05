# Publishing qkt to winget

qkt ships a winget package (`elitekaycy.qkt`) so Windows users can run
`winget install elitekaycy.qkt`. Per-release updates are automated by
`.github/workflows/winget.yml`; the first submission is a one-time manual step.

## One-time bootstrap

1. Fork `microsoft/winget-pkgs` under the account that owns `WINGET_TOKEN`.
2. Create a **fine-grained** Personal Access Token scoped to **only your
   `winget-pkgs` fork**, with **Contents: Read and write** and **Pull requests:
   Read and write** permissions, and add it to the qkt repo secrets as
   `WINGET_TOKEN`. (A classic `public_repo` PAT also works but grants write to
   *all* your public repos — prefer the fork-scoped fine-grained token so an
   exfiltrated token cannot touch anything else.)
3. After the first release that includes `qkt-X.Y.Z-windows-x64.zip`, create the
   initial manifest from a Windows machine (or any host with winget tooling):

   ```powershell
   winget install wingetcreate
   wingetcreate new https://github.com/elitekaycy/qkt/releases/download/vX.Y.Z/qkt-X.Y.Z-windows-x64.zip
   ```

   When prompted: PackageIdentifier `elitekaycy.qkt`, installer type `zip`, nested
   installer type `portable`, nested file `qkt\qkt.exe` with command alias `qkt`.
   Submit the generated PR to `microsoft/winget-pkgs`.

## Thereafter

Every published GitHub release triggers `winget.yml`, which opens the version-bump
PR automatically. No manual action needed once the package exists.

## Install (end users)

```powershell
winget install elitekaycy.qkt
```
