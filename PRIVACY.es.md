# Política de privacidad — Carnelia VPN

[English](PRIVACY.md) · [Русский](PRIVACY.ru.md) · **Español** · [中文](PRIVACY.zh.md) · [العربية](PRIVACY.ar.md) · [Français](PRIVACY.fr.md)

**Última actualización:** julio de 2026

---

## En resumen

Carnelia VPN no recopila, almacena ni transmite ningún dato personal.
Sin servidores de analítica. Sin telemetría. Sin registro. Sin cuentas.

Todo lo que la app guarda permanece en tu dispositivo. No tenemos servidores propios y, por tanto, ninguna forma de saber quién eres ni qué haces.

---

## Lo que NO recopilamos

No recopilamos ni tenemos acceso a:

- tu tráfico de internet, consultas DNS o sitios visitados
- tu dirección IP, ubicación o información del dispositivo
- registros de conexión (marcas de tiempo, duración de sesión)
- ningún identificador de usuario
- informes de fallos, eventos o métricas de uso

La app no contiene ningún SDK de analítica (ni Firebase, Amplitude, Mixpanel ni similares).

---

## Lo que se guarda localmente en tu dispositivo

La app guarda datos **solo en tu dispositivo** mediante Android SharedPreferences y el almacenamiento interno:

| Datos | Dónde se guardan | Finalidad |
|---|---|---|
| Configuraciones de servidor (nombre, dirección, puerto, protocolo, UUID) | SharedPreferences | Funcionamiento de la VPN |
| URL de suscripciones | SharedPreferences | Actualizar la lista de servidores |
| Ajustes (Kill Switch, fragmentación, ruido, etc.) | SharedPreferences | Preferencias de la app |
| Registros del núcleo (opcional) | Almacenamiento interno | Depuración — se eliminan al desinstalar la app |
| Copia cifrada de servidores (opcional) | Archivo que elijas | AES-256, protegido por tu contraseña |

Estos datos **nunca salen de tu dispositivo** y no nos resultan accesibles en ninguna circunstancia.

---

## Suscripciones (Subscription URL)

Si añades una URL de suscripción, la app contacta periódicamente esa dirección para obtener una lista de servidores actualizada. Esta solicitud va **directamente** de tu dispositivo al servidor de suscripción — no somos intermediarios y no vemos ni la solicitud ni la respuesta. Cómo trata los datos ese servidor de suscripción es responsabilidad de su proveedor, no nuestra.

---

## Tráfico de la VPN

Carnelia VPN crea un túnel cifrado con el protocolo que elijas (VLESS, VMess, Trojan, Hysteria2, WireGuard, etc.). Tu tráfico pasa **por el servidor VPN que tú mismo indicaste**. No operamos esos servidores ni somos responsables de las políticas de sus operadores. Elige servidores en los que confíes.

---

## Permisos de Android

La app solicita los siguientes permisos:

| Permiso | Motivo |
|---|---|
| `BIND_VPN_SERVICE` | Crear el túnel VPN mediante la API VpnService de Android |
| `INTERNET` | Conectar al servidor VPN y descargar suscripciones |
| `FOREGROUND_SERVICE` | Mantener la VPN en segundo plano con la pantalla apagada |
| `RECEIVE_BOOT_COMPLETED` | Conexión automática al arrancar el dispositivo (si está activada) |
| `ACCESS_FINE_LOCATION` *(opcional)* | Solo para la función de falseo de GPS (GeoSpoof) — se solicita aparte |
| `MOCK_LOCATION` *(opcional)* | Solo para la función de falseo de GPS |

Ninguno de estos permisos se usa para recopilar datos sobre ti.

---

## Falseo de GPS (GeoSpoof)

La función de falseo de GPS cambia las coordenadas que Android comunica a las apps. Funciona totalmente en local — ninguna coordenada (real o falsa) se nos envía a nosotros ni a terceros. Requiere activar la «app de ubicación simulada» en las opciones de desarrollador de Android.

---

## Chat P2P

El chat P2P opcional funciona sobre la Mainline DHT pública (la red BitTorrent) con cifrado de extremo a extremo. No hay ningún servidor de chat nuestro; los mensajes se intercambian directamente entre pares. No podemos leerlos, almacenarlos ni retransmitirlos.

---

## Menores

La app no está destinada a menores de 13 años. No recopilamos ningún dato, tampoco de menores.

---

## Cambios en esta política

Si la política cambia de forma sustancial, actualizaremos la fecha en la parte superior de este documento. El historial completo de cambios está disponible en el historial de git del repositorio.

---

## Contacto

Preguntas sobre privacidad: abre un issue en [github.com/voksed/carnelia-vpn/issues](https://github.com/voksed/carnelia-vpn/issues).
