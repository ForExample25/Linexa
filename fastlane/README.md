# Fastlane Metadata

Store listing metadata for Linexa `com.fem.linexa`, in the standard Fastlane
layout:

```
fastlane/metadata/android/en-US/
├── title.txt
├── short_description.txt
├── full_description.txt
├── changelogs/1.txt
└── images/
    ├── icon.png                 512×512
    ├── featureGraphic.png       1024×500
    └── phoneScreenshots/1..8.png 1080×1920
```

This tree is used by F-Droid/IzzyOnDroid-style tooling and can be consumed by
Fastlane `supply` for Google Play. It is **not** a submission: no store account
is implied and no listing has been published from this directory.

The source copies of the images live under
`docs/distribution/assets/`. The Fastlane copies exist because store tooling
expects this fixed layout; keep them in sync when the assets change.

Validate with:

```bash
python3 tools/store-publisher/publisher.py lint
```

Character limits (English):

| Field | Limit |
|---|---|
| Title | 30 |
| Short description | 80 |
| Full description | 4,000 |
| Changelog | 500 |

Do not add fake ratings, store badges, or screenshots of a different app.
