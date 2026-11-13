python3 - <<'PY'
import pywemo

devices = pywemo.discover_devices()
print("Found:", devices)

dev = devices[0]
dev.setup(ssid="desveauxds2", password="hershey8")
print("Wi-Fi credentials sent.")
PY
