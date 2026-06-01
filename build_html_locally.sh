#!/bin/sh
TEMPLATES_DIR=docs-templates
HTML_BUILD_DIR=build/payara
ENTERPRISE_DIR=${HTML_BUILD_DIR}/enterprise
COMMUNITY_DIR=${HTML_BUILD_DIR}/community
VERSION=7

echo "Removing directory $HTML_BUILD_DIR"
rm -rf ${HTML_BUILD_DIR}
mkdir -p ${ENTERPRISE_DIR}
mkdir -p ${COMMUNITY_DIR}

echo "Pulling docs-website"
#git clone --depth 1 https://gitlab.azulsystems.com/docs/docs-website.git
git clone --branch feature/payara --depth 1 https://gitlab.azulsystems.com/docs/docs-website.git

mkdir -p ${HTML_BUILD_DIR}/assets/
cp -a docs-website/assets/* ${HTML_BUILD_DIR}/assets/

echo "Pulling docs-templates"
#git clone --depth 1 https://gitlab.azulsystems.com/docs/docs-templates.git
git clone --branch feature/payara --depth 1 https://gitlab.azulsystems.com/docs/docs-templates.git

build_merged() {
  CONTENT_DIR=$1
  OUTPUT_DIR=$2
  shift 2
  # Merge content dir with shared content into a temp directory, then build.
  # content_shared provides pages referenced by both enterprise and community
  # that are not duplicated in either content dir.
  MERGED=$(mktemp -d)
  cp -a ${CONTENT_DIR}/. ${MERGED}/
  cp -a content_shared/. ${MERGED}/
  ruby $TEMPLATES_DIR/build.rb "$@" -d ${MERGED} -o ${OUTPUT_DIR}
  rm -rf ${MERGED}
}

echo "Building content Enterprise"
build_merged content_enterprise ${ENTERPRISE_DIR} \
  --export-public-markdown --generate-sitemap --site-url https://docs.azul.com/payara \
  --generate-index --index-group-id "payara" --index-group-label "Azul Payara" --index-version "${VERSION}" \
  -a ca --page-extension ".html"

echo "Building content Community"
build_merged content_community ${COMMUNITY_DIR} \
  --export-public-markdown --generate-sitemap --site-url https://docs.azul.com/payara-community \
  --generate-index --index-group-id "payara-community" --index-group-label "Azul Payara Community" --index-version "${VERSION}" \
  -a ca --page-extension ".html"

echo "Generating index.html"
cat > ${HTML_BUILD_DIR}/index.html << 'HTML'
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Azul Payara Documentation</title>
  <style>
    body { font-family: sans-serif; max-width: 600px; margin: 80px auto; padding: 0 20px; }
    h1 { font-size: 1.6rem; margin-bottom: 0.5rem; }
    p { color: #555; margin-bottom: 2rem; }
    ul { list-style: none; padding: 0; }
    li { margin-bottom: 1rem; }
    a { font-size: 1.1rem; color: #0066cc; text-decoration: none; }
    a:hover { text-decoration: underline; }
    .desc { font-size: 0.9rem; color: #666; display: block; margin-top: 2px; }
  </style>
</head>
<body>
  <h1>Azul Payara Documentation</h1>
  <p>Local build — choose a documentation set:</p>
  <ul>
    <li>
      <a href="enterprise/">Payara Enterprise</a>
      <span class="desc">Commercial distribution — enterprise features and support</span>
    </li>
    <li>
      <a href="community/">Payara Community</a>
      <span class="desc">Open-source community distribution</span>
    </li>
  </ul>
</body>
</html>
HTML

echo "Stopping already started webserver"
killall -9 jwebserver 2>/dev/null || true

echo "Starting webserver"
. ~/.sdkman/bin/sdkman-init.sh
sdk use java 21.0.1.fx-zulu
cd $HTML_BUILD_DIR
jwebserver &

echo "Opening browser"
open "http://127.0.0.1:8000/"