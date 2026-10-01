# Nodus Android scope

- This repository owns only the Nodus Android client. Nodus Server owns the backend and API.
- Keep this fork thin and easy to merge with upstream Quillpad.
- Coordinate cross-repository work through intercom.
- Do not start implementation without explicit user approval.
- Do not automatically commit, push, rebase, package, deploy, or run destructive actions.
- Do not invent product requirements. Ask for clarification when requirements are missing.

## Release approval before push

- Before any push containing a feature or fix that users need an updated app to use, ask whether to release it. Do not treat pushing source code as delivering an app update.
- Documentation-only and behavior-preserving no-op changes do not require a release or an APK build solely for publication. Run checks appropriate to the changes; existing CI may still build automatically.
- When a release is approved, confirm the version and use the existing version-tagged CI workflow to build and publish the signed APK for Obtainium. A branch push builds a debug APK; it does not publish a release.
- Follow the pipeline to completion. Verify the signed APK, release assets, and published version before reporting the feature or fix as released. Report failures or pending work explicitly.
- If the user declines or defers a release, report that the source changes are pushed but are not yet available as an app update.
- Release approval does not authorize installing the APK on a device or changing live server systems.
