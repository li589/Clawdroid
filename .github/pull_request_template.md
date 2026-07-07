## Summary

- Describe the goal of this PR.

## Changes

- List the main code or documentation changes.

## Verification

- [ ] `go test ./...` in `ClawRuntime/runtime`
- [ ] `.\gradlew.bat :app:compileDebugKotlin` in `ClawApp`
- [ ] `.\gradlew.bat testDebugUnitTest` in `ClawApp` when App unit tests are affected
- [ ] `.\scripts\build-runtime.ps1` in `ClawRuntime` when Runtime build flow is affected
- [ ] `.\scripts\build-magisk.ps1` in `ClawRuntime` when Magisk packaging is affected

## Security / Protocol Impact

- [ ] No security boundary changes
- [ ] Security boundary changed and related docs were updated
- [ ] Protocol changed and `Docs/protocol.md` was updated

## Checklist

- [ ] No real shared secret or local private config was committed
- [ ] Related docs were updated
- [ ] Paths, commands, and file names in docs were checked
