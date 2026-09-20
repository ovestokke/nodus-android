# Nodus Android

Nodus Android is the planned Android client for Nodus, a single-user notes and checklist platform backed by Nodus Server.

## Current status

Nodus Android is under active development. The current working tree includes Nodus Server synchronization against the v2 contract and uses the Android application ID `com.vstokke.nodus`. Device enrollment uses a short-lived, single-use code or `nodus://pair` QR link created by the authenticated Nodus web app; Android never asks the user to paste a bearer token. No production release or store listing exists yet. The note editor, checklist, Markdown, storage, and much of the Android UI remain inherited from Quillpad.

## Lineage and upstream

Nodus Android is a fork of [Quillpad](https://github.com/quillpad/quillpad). Quillpad is the maintained community fork of [Quillnote](https://github.com/msoultanidis/quillnote), created after development of the original project stopped.

The Nodus fork is intended to stay thin and easy to merge with Quillpad. Upstream work remains credited, and Quillpad's repository is the source for documentation about the inherited application.

## License

This repository retains Quillpad's [GNU General Public License v3.0](LICENSE). Upstream copyright and attribution remain in place.
