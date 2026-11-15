/* Jembatan antara aplikasi web (index.html) dan fitur native Android.
   Membuat navigator.bluetooth, window.print(), dan unduhan file bekerja
   di dalam WebView tanpa mengubah kode aplikasi asli. */
(function () {
  var AB = window.AndroidBridge;
  if (!AB) return;

  var pending = {};
  var seq = 0;

  window.__nativeCb = function (id, ok, value) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) p.res(value); else p.rej(new Error(String(value)));
  };

  function call(fn) {
    return new Promise(function (res, rej) {
      var id = ++seq;
      pending[id] = { res: res, rej: rej };
      try { fn(id); } catch (e) { delete pending[id]; rej(e); }
    });
  }

  function toB64(bytes) {
    var bin = '';
    var step = 0x8000;
    for (var i = 0; i < bytes.length; i += step) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    }
    return btoa(bin);
  }

  function normUuid(u) {
    if (typeof u === 'number') {
      return ('0000' + u.toString(16)).slice(-4).replace(/^(.*)$/, '0000$1-0000-1000-8000-00805f9b34fb');
    }
    u = String(u).toLowerCase();
    if (/^[0-9a-f]{4}$/.test(u)) return '0000' + u + '-0000-1000-8000-00805f9b34fb';
    return u;
  }

  /* ---------------- Web Bluetooth (BLE) ---------------- */
  var devices = {};

  function makeChar(addr, svcUuid, c) {
    var ch = {
      uuid: c.uuid,
      properties: {
        write: !!c.write, writeWithoutResponse: !!c.writeNoResp,
        read: false, notify: false, indicate: false
      },
      writeValue: function (v) {
        var bytes;
        if (v instanceof ArrayBuffer) bytes = new Uint8Array(v);
        else bytes = new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
        var b64 = toB64(bytes);
        return call(function (id) { AB.btWrite(id, addr, svcUuid, c.uuid, b64); });
      }
    };
    ch.writeValueWithResponse = ch.writeValue;
    ch.writeValueWithoutResponse = ch.writeValue;
    return ch;
  }

  function makeService(addr, s) {
    return {
      uuid: s.uuid,
      getCharacteristics: function () {
        return Promise.resolve(s.chars.map(function (c) { return makeChar(addr, s.uuid, c); }));
      },
      getCharacteristic: function (u) {
        u = normUuid(u);
        var found = s.chars.filter(function (c) { return c.uuid.toLowerCase() === u; })[0];
        return found ? Promise.resolve(makeChar(addr, s.uuid, found))
                     : Promise.reject(new Error('Characteristic tidak ditemukan'));
      }
    };
  }

  function Device(info, allowed) {
    var self = this;
    var listeners = {};
    var services = [];
    this.id = info.address;
    this.name = info.name || null;
    this.addEventListener = function (t, f) { (listeners[t] = listeners[t] || []).push(f); };
    this.removeEventListener = function (t, f) {
      listeners[t] = (listeners[t] || []).filter(function (x) { return x !== f; });
    };
    this._emit = function (t) {
      (listeners[t] || []).slice().forEach(function (f) { try { f({ type: t, target: self }); } catch (e) {} });
    };
    this.gatt = {
      device: self,
      connected: false,
      connect: function () {
        return call(function (id) { AB.btConnect(id, info.address); }).then(function (r) {
          var all = r.services || [];
          var filtered = all.filter(function (s) { return allowed.indexOf(s.uuid.toLowerCase()) !== -1; });
          if (!filtered.length) {
            var generic = ['00001800', '00001801', '0000180a'];
            filtered = all.filter(function (s) { return generic.indexOf(s.uuid.toLowerCase().slice(0, 8)) === -1; });
          }
          services = filtered;
          self.gatt.connected = true;
          return self.gatt;
        });
      },
      disconnect: function () {
        self.gatt.connected = false;
        try { AB.btDisconnect(info.address); } catch (e) {}
        self._emit('gattserverdisconnected');
      },
      getPrimaryServices: function () {
        return Promise.resolve(services.map(function (s) { return makeService(info.address, s); }));
      },
      getPrimaryService: function (u) {
        u = normUuid(u);
        var s = services.filter(function (x) { return x.uuid.toLowerCase() === u; })[0];
        return s ? Promise.resolve(makeService(info.address, s))
                 : Promise.reject(new Error('Service tidak ditemukan'));
      }
    };
  }

  window.__btDisconnected = function (addr) {
    var d = devices[addr];
    if (d) { d.gatt.connected = false; d._emit('gattserverdisconnected'); }
  };

  var bluetooth = {
    requestDevice: function (opts) {
      opts = opts || {};
      var allowed = (opts.optionalServices || []).map(normUuid);
      (opts.filters || []).forEach(function (f) {
        (f.services || []).forEach(function (s) { allowed.push(normUuid(s)); });
      });
      return call(function (id) { AB.btRequestDevice(id); }).then(function (info) {
        var d = new Device(info, allowed);
        devices[info.address] = d;
        return d;
      });
    },
    getAvailability: function () { return Promise.resolve(true); }
  };
  try {
    Object.defineProperty(navigator, 'bluetooth', { value: bluetooth, configurable: true });
  } catch (e) { navigator.bluetooth = bluetooth; }

  /* ---------------- Cetak (window.print) ---------------- */
  window.print = function () { AB.print(); };

  /* ---------------- Unduh file (CSV / Excel) ---------------- */
  function saveBase64(name, mime, b64) { AB.saveFile(name, mime || 'application/octet-stream', b64); }

  function handleDownload(a) {
    var href = a.href || a.getAttribute('href') || '';
    var name = a.getAttribute('download') || 'file';
    if (href.indexOf('data:') === 0) {
      var comma = href.indexOf(',');
      var meta = href.substring(5, comma);
      var data = href.substring(comma + 1);
      var mime = meta.split(';')[0] || 'text/plain';
      if (/;base64/i.test(meta)) {
        saveBase64(name, mime, data);
      } else {
        var text;
        try { text = decodeURIComponent(data); } catch (e) { text = unescape(data); }
        saveBase64(name, mime, toB64(new TextEncoder().encode(text)));
      }
    } else if (href.indexOf('blob:') === 0) {
      fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
        var fr = new FileReader();
        fr.onload = function () {
          saveBase64(name, blob.type, String(fr.result).split(',')[1] || '');
        };
        fr.readAsDataURL(blob);
      });
    }
  }

  document.addEventListener('click', function (e) {
    var el = e.target;
    var a = el && el.closest ? el.closest('a[download]') : null;
    if (a) { e.preventDefault(); e.stopPropagation(); handleDownload(a); }
  }, true);

  /* ---------------- Tombol Kembali Android ---------------- */
  window.__onBack = function () {
    try {
      if (typeof currentActiveViewId === 'undefined') return false;
      if (currentActiveViewId === 'viewSettings') { closeSettingsView(); return true; }
      if (currentActiveViewId === 'viewBon') { showHomeView(); return true; }
    } catch (e) {}
    return false;
  };
})();
