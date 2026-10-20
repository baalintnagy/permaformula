# Project website

The buildless GitHub Pages site lives in this directory. It follows the Builder's
palette, typography and panel styling, including the visitor's system color scheme.
No external fonts, JavaScript or API calls are required.

The expected address is https://baalintnagy.github.io/permaformula/ and the live
Builder is https://permaformula.kozow.com/.

## Publishing

In the repository's **Settings → Pages**, select **GitHub Actions** as the source.
Push to `main` or run **Deploy project website to GitHub Pages** manually. The
workflow uploads only `site/`; the Java application is deployed separately.

If the repository owner, name or Pages domain changes, update the canonical URL,
social image URLs, structured data, sitemap and robots file together. GitHub project
sites cannot control the origin-level `/robots.txt`; the included project file is
informational unless this site is hosted at an origin root. The sitemap can be
submitted directly to search engines.

The landing page and Builder have distinct canonical URLs and descriptions. Both
include social sharing metadata and structured data. The Builder serves its own
`/robots.txt` and `/sitemap.xml` using `PERMAFORMULA_HOST`; ensure that setting is the
public HTTPS origin. Only the Builder root is listed because workspaces use URL
fragments and generated image URLs are not separate editorial pages.

For local preview, serve this directory with any static HTTP server. Check both
system color schemes and a narrow viewport before changing the layout.
