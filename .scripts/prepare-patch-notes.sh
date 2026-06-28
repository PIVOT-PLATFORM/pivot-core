#!/bin/sh
# Renames "## [Unreleased]" to "## [x.y.z] — DD mois YYYY" in PATCH_NOTES.md
# Called by @semantic-release/exec prepareCmd with version as $1
VERSION=$1
DATE_FR=$(node -e "const m=['janvier','février','mars','avril','mai','juin','juillet','août','septembre','octobre','novembre','décembre'];const d=new Date();process.stdout.write(d.getDate()+' '+m[d.getMonth()]+' '+d.getFullYear())")
sed -i "s/## \[Unreleased\]/## [$VERSION] — $DATE_FR/" PATCH_NOTES.md
