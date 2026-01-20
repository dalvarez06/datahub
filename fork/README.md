# Fork Maintenance Notes

This folder keeps fork-specific guidance to make upstream rebases easier and more repeatable.

## Contents
- `CHANGELOG.md`: concise summary of what differs from `upstream/master` and why.

## Suggested workflow
1) Update `fork/CHANGELOG.md` after each feature batch or before rebasing.
2) Use the “Merge Watchlist” section to anticipate conflict hotspots.
3) Keep additional fork-only docs here to avoid mixing with upstream docs.

## Optional automation ideas
- A periodic job that regenerates `fork/CHANGELOG.md` from `git log upstream/master..HEAD`.
- A merge agent that reads `fork/CHANGELOG.md` before pulling upstream to resolve conflicts faster.
