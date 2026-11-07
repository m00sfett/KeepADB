# Keep the package name (`applicationId`) as `de.hohnepeople.keepadb`

Decision made on 2026-10-05 by the user during the website move to `keepadb.roteson.de`.

## Decision

Keep `applicationId` and `namespace` as `de.hohnepeople.keepadb`. The package name is a
historical identifier and does not need to match a domain. It is not shown to users. The website
move changes URLs only (Settings, feedback link, and store metadata), not the app identity.

## Rationale

- Android treats a new `applicationId` as a different app: there is no update or transfer of
  settings and trusted networks, even with the same signing key. Users would have to set up the new
  app and could temporarily have two apps installed.
- A new version number, including 2.0.0, does not change this; the identity depends only on the ID.
- F-Droid lists apps by package name. A new ID would need a new inclusion merge request and review;
  the existing entry could not be redirected. The update history would be lost.
## What changes instead

- New links (`https://keepadb.roteson.de` and feedback under `/feedback`) ship with `v1.11.0`
  (issue #754).
- fdroiddata merge request !55473, submitted and merged on 2026-11-02 after the `v1.11.0`
  release, changed `WebSite:` in `fdroiddata/metadata/de.hohnepeople.keepadb.yml` from
  `https://hohnepeople.de/keepadb` to `https://keepadb.roteson.de`.
- Older installed versions (through 1.8.38) will continue opening hohnepeople.de; that site
  redirects to the new website. For the TLS chain note, see issue #749.

## When to reconsider

Only as part of a deliberately planned hard reset of the app identity, with data breakage accepted.
There is currently no reason to do this.
