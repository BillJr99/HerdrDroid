# Card preview regression fixture

Run `./gradlew testDebugUnitTest --tests '*CardPreviewReadTest'` with
`HD_PREVIEW_FIXTURE_DIR` pointing to a disposable directory shared with an isolated
SSH server. The tests skip without this variable. The fixture expects:

- SSH on `127.0.0.1:2222`, user `dev`, with a disposable key in `client-key` and
  its public key in the server's authorized keys.
- The fixture server's SHA256 host fingerprint in `pin`.
- `herdr.py` copied as executable `preview-cli` in that directory, at the same
  absolute path on both sides. The Gradle and SSH users need write access to
  the directory for synthetic screen and command-trace files.

Use no real Herdr server. The CLI implements only passive test reads and a
bulk command that waits for a release marker; it does not accept input commands.
