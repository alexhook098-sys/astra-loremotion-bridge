#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG="com.astra.loremotionbridge"
ENTRY="${PKG}/.FlowAutomationEntryActivity"
WAIT_SECONDS=120
PROMPT=""

if [ "${1:-}" ]; then
  if [[ "$1" =~ ^[0-9]+$ ]]; then
    WAIT_SECONDS="$1"
    PROMPT="${2:-}"
  else
    PROMPT="$1"
  fi
fi

if [ -z "$PROMPT" ]; then
  printf "Промпт для Google Flow: "
  IFS= read -r PROMPT
fi

if [ -z "${PROMPT//[[:space:]]/}" ]; then
  echo "Промпт пустой."
  exit 1
fi

if ! command -v am >/dev/null 2>&1; then
  echo "ОШИБКА: запускай этот файл в Termux на Android."
  exit 1
fi

echo "[1/4] Открываю Google Flow WebView внутри ASTRA..."
am start -n "$ENTRY" --es astra_flow_mode start --es astra_flow_prompt "$PROMPT"

echo "[2/4] ASTRA вводит промпт и нажимает Generate."
echo "[3/4] Жду ${WAIT_SECONDS} секунд. Flow работает в фоне..."
sleep "$WAIT_SECONDS"

echo "[4/4] Возвращаю Flow. ASTRA нажмёт последнюю карточку и запустит скачивание."
am start -n "$ENTRY" --es astra_flow_mode resume

echo "Готово: ожидай сообщение с видео в ASTRA Chat после завершения скачивания."
