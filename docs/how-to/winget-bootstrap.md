# Publishing qkt to winget

qkt ships a winget package (`elitekaycy.qkt`) so Windows users can run
`winget install elitekaycy.qkt`. Per-release updates are automated by
`.github/workflows/winget.yml`; the first submission is a one-time manual step.

## One-time bootstrap

1. Fork `microsoft/winget-pkgs` under the account that owns `WINGET_TOKEN`.
2. Create a classic Personal Access Token with the `public_repo` scope and add it
   to the qkt repo secrets as `WINGET_TOKEN`.
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
