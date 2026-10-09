#!/usr/bin/env bash
# Points the bundle ids, App Group and keychain group at your own Apple team
# so the app runs on your iPhone or iPad. Local only: never commit the result.
#
#   e2e/ios-local-signing.sh <TEAM_ID> [bundle-id-prefix]
#
# Undo with: git checkout -- ios && rm ios/Config/Team.local.xcconfig
set -euo pipefail
team=${1:?usage: e2e/ios-local-signing.sh <TEAM_ID> [bundle-id-prefix]}
prefix=${2:-dev.tost.zsync}
cd "$(dirname "$0")/../ios"
perl -pi -e "s/de\.kjell\.zencompanion/$prefix/g" \
  project.yml \
  ZenCompanion/ZenCompanion.entitlements \
  ShareExtension/ShareExtension.entitlements \
  Shared/Data/AppGroup.swift
printf 'DEVELOPMENT_TEAM = %s\n' "$team" > Config/Team.local.xcconfig
xcodegen
echo "Ready: open ios/ZenCompanion.xcodeproj and run on your device."
