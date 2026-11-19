# Usage recovery regression

`UsageRecoveryTest` exercises the actual ViewModel and SSH client against `herdr.py`, a synthetic CLI. Its successful recent capture shows the draft, while the live viewport starts on a usage panel. Check that observing the panel sends nothing, a changed approval screen rejects the stale action, and a valid explicit dismissal sends exactly one Esc and leaves the original draft intact.

To run the opt-in test, use a disposable Linux SSH container with Python 3.10+, a `dev` user and two freshly generated fixture-only SSH keys. Publish the SSH port on **127.0.0.1 only**, install the test client public key in `dev`'s `authorized_keys`, and mount `herdr.py` executable at `/fixture/herdr`. Give `dev` a writable, initially empty `/fixture/state` directory. Place the generated test client private key at `client` and the test host key's `SHA256:…` fingerprint at `pin` in a private local directory. Do not mount real server state or reuse real keys.

```sh
HD_USAGE_FIXTURE_DIR=/path/to/private/fixture HD_USAGE_FIXTURE_PORT=12345 \
  bash gradlew :app:testDebugUnitTest --tests '*UsageRecoveryTest'
```

The default unit suite skips this SSH scenario unless the fixture is configured. `UsageOverlayTest` separately exercises the production server-side guard against a synthetic CLI without SSH, including changes between reads and pane occupant replacement. Herdr does not provide an atomic compare-and-send API: a live change between the final check and the key command remains possible.
