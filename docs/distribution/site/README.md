# Linexa Pages Site

Static source for <https://forexample25.github.io/Linexa/>.

- `index.html` — landing, download, screenshots, install, verification,
  distribution status, project links.
- `privacy.html` — public privacy policy.
- `release.json` — machine-readable release facts.
- `assets/` — icon, feature graphic, screenshots, favicon.
- `.nojekyll` — serve files as-is.
- `.well-known/security.txt` — security report contact.
- `404.html`, `robots.txt`, `sitemap.xml`.

No external fonts, analytics, cookies, or third-party scripts. The only
JavaScript is the local copy-to-clipboard button.

## Deploy

The site is published from an **orphan `gh-pages` branch**, not from `main`.
This keeps the source tree and release tags untouched.

```bash
# From a clean temporary directory
git init
git checkout --orphan gh-pages
cp -R /path/to/repo/docs/distribution/site/. .
git add .
git commit -m "Deploy Linexa distribution site"
git remote add origin https://github.com/ForExample25/Linexa.git
git push origin gh-pages
```

Then enable Pages:

```bash
gh api --method POST repos/ForExample25/Linexa/pages \
  -f 'source[branch]=gh-pages' \
  -f 'source[path]=/'
```

If Pages already exists, use `PUT` instead of `POST`. Verify:

```bash
curl -fsS https://forexample25.github.io/Linexa/ | grep -F '0.1.0-beta'
curl -fsS https://forexample25.github.io/Linexa/privacy.html | grep -F 'Effective date'
```

Do not put an APK in this branch. The download button points at the verified
GitHub Release asset.
