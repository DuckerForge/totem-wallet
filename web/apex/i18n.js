// Both languages in one file: these pages are opened by whoever was handed the
// link, not by the person who made it, so the phone's own language wins.
const IT = navigator.language && navigator.language.toLowerCase().startsWith('it');
const T = (it, en) => (IT ? it : en);
const APK = 'https://duckerforge.github.io/apex/';
const STORE = 'solanadappstore://details?id=com.clearsign.app';

function shorten(s, n = 4) {
  return s.length <= n * 2 + 3 ? s : s.slice(0, n) + '…' + s.slice(-n);
}

function brand(sub) {
  document.getElementById('brand').innerHTML =
    '<img src="../icon.png" alt=""><div><b>Apex</b><span>' + sub + '</span></div>';
}

// Base58 (Bitcoin alphabet), because the gift key travels that way.
const B58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function b58decode(str) {
  const bytes = [0];
  for (const ch of str) {
    const v = B58.indexOf(ch);
    if (v < 0) throw new Error('bad base58');
    let carry = v;
    for (let i = 0; i < bytes.length; i++) {
      carry += bytes[i] * 58;
      bytes[i] = carry & 0xff;
      carry >>= 8;
    }
    while (carry > 0) { bytes.push(carry & 0xff); carry >>= 8; }
  }
  for (let i = 0; i < str.length && str[i] === '1'; i++) bytes.push(0);
  return new Uint8Array(bytes.reverse());
}
