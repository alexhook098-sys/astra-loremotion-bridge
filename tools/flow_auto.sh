#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG="com.astra.loremotionbridge"
ENTRY="${PKG}/.FlowAutomationEntryActivity"
WAIT_SECONDS="${1:-120}"

if ! command -v am >/dev/null 2>&1; then
  echo "ОШИБКА: команда am недоступна. Запусти скрипт в Termux на Android."
  exit 1
fi

if [ "$#" -gt 1 ]; then
  PROMPT="$2"
else
  printf "Промпт для Google Flow: "
  IFS= read -r PROMPT
fi

if [ -z "${PROMPT//[[:space:]]/}" ]; then
  echo "Промпт пустой."
  exit 1
fi

echo "[1/4] Запускаю Flow WebView в ASTRA..."
am start -n "$ENTRY" --es astra_flow_mode start --es astra_flow_prompt "$PROMPT"

echo "[2/4] ASTRA вводит промпт и запускает Generate."
echo "[3/4] Жду ${WAIT_SECONDS} секунд, Flow остаётся в фоне..."
sleep "$WAIT_SECONDS"

echo "[4/4] Возвращаю Flow на передний план: ASTRA откроет последнюю карточку и попробует скачать MP4."
am start -n "$ENTRY" --es astra_flow_mode resume

echo "Команды отправлены. Результат появится в ASTRA Chat после успешного скачивания."
