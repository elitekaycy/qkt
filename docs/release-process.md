# Release process

## Versioning

qkt uses semantic versions while pre-1.0:

- **minor (`0.X.0`)**: a substantial phase or intentionally breaking public change
- **patch (`0.X.Y`)**: a compatible feature set, sub-phase, or hotfix

`VERSION` is the single release version consumed by Gradle, the CLI, generated
scaffolds, distribution names, and container metadata. Change it through a reviewed
PR before creating a tag.

## Distribution channels

| Channel | Source | Intended use |
|---|---|---|
| GitHub release and `ghcr.io/elitekaycy/qkt:vX.Y.Z` | immutable `vX.Y.Z` tag | canonical stable install |
| `ghcr.io/elitekaycy/qkt:latest` | `main` | current promoted main |
| `ghcr.io/elitekaycy/qkt:edge` | `testing` | integration-tested staging |
| `ghcr.io/elitekaycy/qkt:dev` | `main` | authoring image with editor tools |

Generated deployment projects pin `v<VERSION>`. Do not publish a qkt build whose tag
differs from `VERSION`: both release and Docker workflows run
`scripts/verify-version-tag.sh`, and the essentials workflow tests that gate.

## Promotion

Changes reach a release through the protected branch flow:

1. Merge reviewed feature branches into `dev`.
2. Let the green `check.yml` run fast-forward `dev` to `testing`.
3. Wait for `integration.yml` on the exact `testing` SHA.
4. Run the `promote-to-main` workflow. It verifies that integration evidence and
   creates or reuses a `testing -> main` PR.
5. Merge the promotion PR after its required checks.
6. Wait for `integration.yml` and `docker.yml` on the promoted `main` SHA.

Do not push directly to `dev`, `testing`, or `main`.

## Tag And Publish

Tag the exact promoted main commit only after the main checks pass:

```bash
git fetch origin
release_sha="$(git rev-parse origin/main)"
version="$(tr -d '\r\n' < VERSION)"
scripts/verify-version-tag.sh "v$version"
git tag -a "v$version" "$release_sha" -m "release: v$version"
git push origin "v$version"
```

The tag push starts two pipelines:

- `release.yml` builds and verifies the ordinary tarball, self-contained Linux
  bundle, self-contained Windows zip, and VS Code extension, then creates the GitHub
  release with generated notes.
- `docker.yml` smoke-tests the runtime image and publishes the versioned GHCR image
  with provenance and an SBOM.

The GitHub release event then starts the Winget submission workflow. Do not run
`gh release create` or upload Gradle artifacts manually during the normal path; that
duplicates and can race the automated publisher.

## Verification

Before announcing the release, verify all of the following:

```bash
gh release view "v$version"
gh run list --workflow release.yml --limit 1
gh run list --workflow docker.yml --limit 1
docker pull "ghcr.io/elitekaycy/qkt:v$version"
docker run --rm --entrypoint qkt "ghcr.io/elitekaycy/qkt:v$version" --version
```

The release must contain the Linux tarballs and Windows zip, and `qkt --version` from
the image must report the tagged version and release SHA.

## Failure And Hotfix Policy

Tags are annotated and immutable. Never force-update or delete a published tag. If
the tag is wrong, fix forward with the next patch version and explain the superseded
release in its notes.

For a hotfix, branch from `dev`, merge through `dev -> testing -> main`, increment
`VERSION`, and repeat the same tag workflow. There are no backport branches before
1.0; users update to the latest patch.
