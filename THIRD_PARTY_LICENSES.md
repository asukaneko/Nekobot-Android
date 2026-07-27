# Third-Party Licenses

Nekobot Android includes the following third-party runtime components for its
Agent-mode Linux sandbox. The app's own license remains unchanged.

## OpenMinis PRoot runtime

- Component: `libproot.so`, `libproot-loader.so`, `libproot-loader32.so`
- Upstream project: [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)
- Imported from release: [Android 0.20-preview](https://github.com/OpenMinis/OpenMinis/releases/tag/0.20-preview)
- Release source revision: `9cf3a855fecd27bb5735b84cacbd56852a3ab8dd`
- Release APK SHA-256: `6cfeeaeef598708b646b4658846adfc939c7140c635673a4738e0d42ca40b058`
- PRoot fork: [OpenMinis/proot](https://github.com/OpenMinis/proot/tree/8cf13e997cdc9472997aae19df8050c073c9a86c)
- PRoot source revision: `8cf13e997cdc9472997aae19df8050c073c9a86c`
- License: GPL-2.0-only
- Statically linked allocator: talloc, LGPL-3.0-or-later
- Corresponding build source: [`deps/build_proot.sh`](https://github.com/OpenMinis/OpenMinis/blob/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd/deps/build_proot.sh)

Bundled file checksums:

| File | SHA-256 |
|---|---|
| `libproot.so` | `f6b0381ab9a066fa620fef0001737fd3cfaf9d22474f013ac48d7861411374ac` |
| `libproot-loader.so` | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` |
| `libproot-loader32.so` | `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` |

## Alpine Linux minirootfs

- Component: Alpine Linux 3.21.3 aarch64 minirootfs
- Upstream release: [Alpine 3.21 aarch64 releases](https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/)
- Imported through the verified OpenMinis `0.20-preview` Android release
- Bundled tar SHA-256: `5651126278f52f292d342794ee0c270c2d35e1859f70c06c65497986578115cf`

The minirootfs is an aggregate of separately licensed Alpine packages. Package
names, versions, and SPDX identifiers below were read from its
`/lib/apk/db/installed` database:

| Package | Version | License |
|---|---:|---|
| alpine-baselayout | 3.6.8-r1 | GPL-2.0-only |
| alpine-baselayout-data | 3.6.8-r1 | GPL-2.0-only |
| alpine-keys | 2.5-r0 | MIT |
| alpine-release | 3.21.3-r0 | MIT |
| apk-tools | 2.14.6-r3 | GPL-2.0-only |
| busybox | 1.37.0-r12 | GPL-2.0-only |
| busybox-binsh | 1.37.0-r12 | GPL-2.0-only |
| ca-certificates-bundle | 20241121-r1 | MPL-2.0 AND MIT |
| libcrypto3 | 3.3.3-r0 | Apache-2.0 |
| libssl3 | 3.3.3-r0 | Apache-2.0 |
| musl | 1.2.5-r9 | MIT |
| musl-utils | 1.2.5-r9 | MIT AND BSD-2-Clause AND GPL-2.0-or-later |
| scanelf | 1.3.8-r1 | GPL-2.0-only |
| ssl_client | 1.37.0-r12 | GPL-2.0-only |
| zlib | 1.3.1-r2 | Zlib |

## Included license texts

The APK includes the applicable license texts under
`assets/third_party_licenses/`:

- `GPL-2.0-only.txt`
- `LGPL-3.0-or-later.txt`
- `MIT.txt`
- `Apache-2.0.txt`
- `MPL-2.0.txt`
- `BSD-2-Clause.txt`
- `Zlib.txt`

