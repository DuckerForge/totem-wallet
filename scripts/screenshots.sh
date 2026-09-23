#!/usr/bin/env bash
# Gli otto screenshot per la candidatura, presi dal Seeker attaccato via adb.
#
# Tu navighi sul telefono fino alla schermata detta, premi Invio, lui scatta e
# salva in docs/screenshots/ col nome giusto. Invio a vuoto salta la schermata,
# q esce. Rifallo quante volte vuoi: sovrascrive.
#
#   scripts/screenshots.sh            tutte le otto
#   scripts/screenshots.sh 3 7        solo la 3 e la 7
set -euo pipefail
cd "$(dirname "$0")/.."
out=docs/screenshots
mkdir -p "$out"

if ! adb get-state >/dev/null 2>&1; then
  echo "Nessun telefono via adb. Attacca il Seeker (o riapri il debug wireless) e riprova." >&2
  exit 1
fi

shots=(
  "01-door|La porta: Velum aperto sulla home, saldo visibile, i cerchi delle azioni"
  "02-receipt|Uno scontrino di firma da una dApp: importo, destinatario, badge fidato, «Tieni premuto per firmare»"
  "03-blocked|Lo scontrino di un drainer (dal testdapp): rischio grave in rosso, firma bloccata"
  "04-agent|La pagina Agente con la paghetta aperta: libera, in monete, oggi, il collare"
  "05-eyes|Guardalo lavorare: il grafico con entrata, obiettivo e stop, il ragionamento sotto"
  "06-scout|Scout, la linguetta Comprano: cosa comprano i Seeker attivi"
  "07-ore|ORE: la griglia, il giro in corso, la riga con l'atteso e il costo"
  "08-health|Salute wallet con qualcosa da sistemare, o «Il tuo wallet è pulito» se non c'è niente"
)

pick=("$@")
i=0
for entry in "${shots[@]}"; do
  i=$((i+1))
  name="${entry%%|*}"; what="${entry#*|}"
  if [ ${#pick[@]} -gt 0 ] && ! printf '%s\n' "${pick[@]}" | grep -qx "$i"; then continue; fi
  echo
  echo "[$i/8] $what"
  read -r -p "      Pronto? Invio scatta, Invio a vuoto... (q esce) " answer </dev/tty || true
  case "${answer:-}" in q|Q) echo "Fermo qui."; exit 0;; esac
  adb exec-out screencap -p > "$out/$name.png"
  echo "      -> $out/$name.png ($(du -h "$out/$name.png" | cut -f1))"
done
echo
echo "Fatto. Le immagini sono in $out/. Sono 1200x2670: per la candidatura bastano cosi', o si ritagliano con scripts/video/phone.py."
