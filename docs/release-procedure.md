# Release procedure: CurseForge upload runbook

Manual runbook the operator follows to publish a Beacon release on
CurseForge. Every step is run by hand.

## Pre-flight

- A clean working tree on the release commit.
- Network access to CurseForge, Docker Hub, and Maven Central.
- CurseForge publishing rights on the Beacon project.

## Steps

### 1. Build the release artifacts

```bash
./gradlew :dist:shadowJar :agent-ext:jar :dist:checkShading :dist:verifyServiceFiles
```

All four tasks must succeed. `checkShading` and `verifyServiceFiles` are the
release gates: a failure means the JAR will collide with Hytale-bundled
libraries at runtime. Do not proceed past a failure.

Outputs:

- `dist/build/libs/beacon-0.1.0-alpha.jar` (shaded plugin)
- `agent-ext/build/libs/beacon-agent-0.1.0-alpha.jar` (agent extension)

### 2. Run the release-gate bench

```bash
./gradlew :bench:overheadBench
```

Confirm the median CPU printed by `OverheadHarness` (and recorded in
`docs/perf-quic.md`) is < 1% at 20 players / 10 minutes / 3-run median.

The CI bench gate uses 1.5% as a build blocker for noise. The release gate
is 1.0% hard fail. If the bench lands between 1.0% and 1.5%, abort the
release and investigate.

### 3. Bump the CHANGELOG date

Update the `## [0.1.0-alpha] - YYYY-MM-DD` heading to the actual release
date (UTC) and commit.

### 4. Tag locally

```bash
git tag v0.1.0-alpha
git push origin v0.1.0-alpha
```

### 5. Upload both JARs to CurseForge

1. Go to the Beacon CurseForge project, File Upload.
2. Upload `beacon-0.1.0-alpha.jar`.
3. Upload `beacon-agent-0.1.0-alpha.jar` as an additional file on the same
   release.
4. Set release type to Alpha.
5. Title: `Beacon v0.1.0 Alpha (Pre-release)`.
6. Description: paste the Quickstart section from `README.md`, followed by
   a link to `CHANGELOG.md` on the GitHub repo for the `v0.1.0-alpha` tag.
7. Publish.

### 6. Verify the public CurseForge page

- Title displays `Beacon v0.1.0 Alpha (Pre-release)`.
- Alpha tag visible on the file row.
- Both JARs downloadable from anonymous sessions.
- Description renders the quickstart.
- CHANGELOG link resolves.

### 7. Record the release URL

Keep the public CurseForge URL alongside the checksum of each uploaded JAR
in your local release notes.

## Rollback

If a critical defect is found post-publish:

1. Mark the CurseForge file as Archived. Do not delete; keep it accessible
   for forensic reproduction.
2. Open a tracking issue describing the defect and impact.
3. Issue a follow-up alpha (`v0.1.1-alpha`) with the fix. Do not re-use the
   `v0.1.0-alpha` tag.

## CurseForge platform notes

- Alpha release type means no auto-update on the stable channel.
- File size limit is currently 200 MB; Beacon's shaded JAR is well under.
- The CurseForge API supports automated uploads via personal API tokens.
  Uploads are performed manually for this project.
