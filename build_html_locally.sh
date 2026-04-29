#!/bin/sh
TEMPLATES_DIR=docs-templates
HTML_BUILD_DIR=build/payara
VERSION=26-ga

echo "Removing directory $HTML_BUILD_DIR"
rm -rf ${HTML_BUILD_DIR}
mkdir -p ${HTML_BUILD_DIR}/

echo "Pulling docs-website"
#git clone --depth 1 https://gitlab.azulsystems.com/docs/docs-website.git
git clone --branch feature/payara --depth 1 https://gitlab.azulsystems.com/docs/docs-website.git

mkdir -p ${HTML_BUILD_DIR}/assets/
cp -a docs-website/assets/* ${HTML_BUILD_DIR}/assets/

echo "Pulling docs-templates"
#git clone --depth 1 https://gitlab.azulsystems.com/docs/docs-templates.git
git clone --branch feature/payara --depth 1 https://gitlab.azulsystems.com/docs/docs-templates.git

echo "Building content"
ruby $TEMPLATES_DIR/build.rb \
  --export-public-markdown --generate-sitemap --site-url https://docs.azul.com/payara \
  --generate-index --index-group-id "payara" --index-group-label "Azul Payara" --index-version "${VERSION}" \
  -d content -o $HTML_BUILD_DIR -a ca -a RELEASE_ID=${VERSION} --page-extension ".html"

echo "Stopping already started webserver"
killall -9 jwebserver

echo "Starting webserver"
. ~/.sdkman/bin/sdkman-init.sh
sdk use java 21.0.1.fx-zulu
cd $HTML_BUILD_DIR
jwebserver &

echo "Opening browser"
open "http://127.0.0.1:8000/"