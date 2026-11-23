#!/bin/sh
# Make ARMFlash's release signing key (~/.android/armflash-release.jks) and
# android/keystore.properties, which the release build reads. Run it once, in a terminal:
# it asks for a password, which stays on this Mac.
set -e
KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
STORE="$HOME/.android/armflash-release.jks"
PROPS="$(cd "$(dirname "$0")/.." && pwd)/android/keystore.properties"

if [ -e "$STORE" ]; then
  echo "$STORE already exists, so nothing was changed."
  exit 1
fi
if [ ! -x "$KEYTOOL" ]; then
  echo "keytool was not found at $KEYTOOL (it comes with Android Studio)."
  exit 1
fi

printf 'Choose a password for the key (at least 6 characters; what you type is hidden): '
stty -echo; read -r PW; stty echo; echo
printf 'Type it again: '
stty -echo; read -r PW2; stty echo; echo
if [ "$PW" != "$PW2" ]; then echo "The two passwords are different. Run it again."; exit 1; fi
if [ ${#PW} -lt 6 ]; then echo "Use at least 6 characters. Run it again."; exit 1; fi

mkdir -p "$HOME/.android"
ARMFLASH_PW="$PW" "$KEYTOOL" -genkeypair -keystore "$STORE" -storetype PKCS12 -alias armflash \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=jpolo1224" \
  -storepass:env ARMFLASH_PW -keypass:env ARMFLASH_PW

# A backslash is special in .properties files, so it is written doubled.
PW_PROP=$(printf '%s' "$PW" | sed 's/\\/\\\\/g')
umask 077
cat > "$PROPS" <<EOF
storeFile=$STORE
storePassword=$PW_PROP
keyAlias=armflash
keyPassword=$PW_PROP
EOF

echo
echo "Done. Your release key is $STORE"
echo "Keep a copy of that file and its password somewhere safe (a USB stick, a cloud drive,"
echo "a password manager). Without them, no later version of ARMFlash can update this one."
