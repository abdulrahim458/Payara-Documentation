#!/bin/sh
TEMPLATES_DIR=docs-templates
PDF_BUILD_DIR=build/payara/pdf

ENTERPRISE_RELEASE_ID=$(grep "^release-id" content_enterprise/project.properties | cut -d'=' -f2 | tr -d ' ')
COMMUNITY_RELEASE_ID=$(grep "^release-id" content_community/project.properties | cut -d'=' -f2 | tr -d ' ')

echo "Enterprise release: ${ENTERPRISE_RELEASE_ID}"
echo "Community release:  ${COMMUNITY_RELEASE_ID}"

echo "Removing directory $PDF_BUILD_DIR"
rm -rf ${PDF_BUILD_DIR}
mkdir -p ${PDF_BUILD_DIR}

if [ ! -d "${TEMPLATES_DIR}" ]; then
  echo "Pulling docs-templates"
  git clone --branch feature/payara --depth 1 https://gitlab.azulsystems.com/docs/docs-templates.git
fi

build_pdf() {
  CONTENT_DIR=$1
  RELEASE_ID=$2
  DOCS_ID=$3
  MERGED=$(mktemp -d)
  cp -a ${CONTENT_DIR}/. ${MERGED}/
  cp -a content_shared/. ${MERGED}/
  ruby ${TEMPLATES_DIR}/build.rb \
    -d ${MERGED} \
    -f pdf \
    -a ca \
    -a pdf_file_name="${DOCS_ID}-${RELEASE_ID}" \
    -a RELEASE_ID=${RELEASE_ID} \
    -o ${PDF_BUILD_DIR}
  rm -rf ${MERGED}
}

echo "Building PDF for Payara Enterprise (${ENTERPRISE_RELEASE_ID})"
build_pdf content_enterprise ${ENTERPRISE_RELEASE_ID} payara

echo "Building PDF for Payara Community (${COMMUNITY_RELEASE_ID})"
build_pdf content_community ${COMMUNITY_RELEASE_ID} payara-community

echo "Generated PDFs:"
ls -lh ${PDF_BUILD_DIR}
