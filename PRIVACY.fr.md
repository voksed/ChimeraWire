# Politique de confidentialité — Carnelia VPN

[English](PRIVACY.md) · [Русский](PRIVACY.ru.md) · [Español](PRIVACY.es.md) · [中文](PRIVACY.zh.md) · [العربية](PRIVACY.ar.md) · **Français**

**Dernière mise à jour :** juillet 2026

---

## En bref

Carnelia VPN ne collecte, ne stocke ni ne transmet aucune donnée personnelle.
Aucun serveur d'analytique. Aucune télémétrie. Aucune inscription. Aucun compte.

Tout ce que l'appli enregistre reste sur votre appareil. Nous n'avons pas de serveurs à nous, et donc aucun moyen de savoir qui vous êtes ni ce que vous faites.

---

## Ce que nous ne collectons PAS

Nous ne collectons pas et n'avons pas accès à :

- votre trafic internet, vos requêtes DNS ou les sites visités
- votre adresse IP, votre position ou les informations de l'appareil
- les journaux de connexion (horodatages, durée des sessions)
- tout identifiant utilisateur
- les rapports de plantage, événements ou métriques d'usage

L'appli ne contient aucun SDK d'analytique (ni Firebase, Amplitude, Mixpanel ou similaire).

---

## Ce qui est stocké localement sur votre appareil

L'appli enregistre les données **uniquement sur votre appareil** via Android SharedPreferences et le stockage interne :

| Données | Emplacement | Finalité |
|---|---|---|
| Configurations de serveur (nom, adresse, port, protocole, UUID) | SharedPreferences | Fonctionnement du VPN |
| URL d'abonnement | SharedPreferences | Mise à jour de la liste des serveurs |
| Paramètres (Kill Switch, fragmentation, bruit, etc.) | SharedPreferences | Préférences de l'appli |
| Journaux du cœur (facultatif) | Stockage interne | Débogage — supprimés à la désinstallation de l'appli |
| Sauvegarde chiffrée des serveurs (facultative) | Fichier que vous choisissez | AES-256, protégée par votre mot de passe |

Ces données **ne quittent jamais votre appareil** et ne nous sont accessibles en aucune circonstance.

---

## Abonnements (Subscription URL)

Si vous ajoutez une URL d'abonnement, l'appli contacte régulièrement cette adresse pour récupérer une liste de serveurs à jour. Cette requête part **directement** de votre appareil vers le serveur d'abonnement — nous ne sommes pas un intermédiaire et ne voyons ni la requête ni la réponse. La manière dont ce serveur d'abonnement traite les données relève de la responsabilité de son fournisseur, pas de la nôtre.

---

## Trafic VPN

Carnelia VPN crée un tunnel chiffré avec le protocole que vous choisissez (VLESS, VMess, Trojan, Hysteria2, WireGuard, etc.). Votre trafic passe **par le serveur VPN que vous avez vous-même indiqué**. Nous n'exploitons pas ces serveurs et ne sommes pas responsables des politiques de leurs opérateurs. Choisissez des serveurs de confiance.

---

## Autorisations Android

L'appli demande les autorisations suivantes :

| Autorisation | Raison |
|---|---|
| `BIND_VPN_SERVICE` | Créer le tunnel VPN via l'API VpnService d'Android |
| `INTERNET` | Se connecter au serveur VPN et télécharger les abonnements |
| `FOREGROUND_SERVICE` | Garder le VPN actif en arrière-plan, écran éteint |
| `RECEIVE_BOOT_COMPLETED` | Connexion auto au démarrage de l'appareil (si activée) |
| `ACCESS_FINE_LOCATION` *(facultative)* | Uniquement pour la falsification GPS (GeoSpoof) — demandée séparément |
| `MOCK_LOCATION` *(facultative)* | Uniquement pour la falsification GPS |

Aucune de ces autorisations n'est utilisée pour collecter des données à votre sujet.

---

## Falsification GPS (GeoSpoof)

La fonction de falsification GPS modifie les coordonnées qu'Android communique aux applis. Elle fonctionne entièrement en local — aucune coordonnée (réelle ou fausse) n'est envoyée à nous ni à un tiers. Elle nécessite d'activer « l'appli de position fictive » dans les options développeur d'Android.

---

## Chat P2P

Le chat P2P facultatif fonctionne sur la Mainline DHT publique (le réseau BitTorrent) avec un chiffrement de bout en bout. Nous n'avons aucun serveur de chat ; les messages sont échangés directement entre pairs. Nous ne pouvons ni les lire, ni les stocker, ni les relayer.

---

## Enfants

L'appli n'est pas destinée aux personnes de moins de 13 ans. Nous ne collectons aucune donnée, y compris auprès des enfants.

---

## Modifications de cette politique

En cas de modification substantielle de la politique, nous mettrons à jour la date en haut de ce document. L'historique complet des modifications est disponible dans l'historique git du dépôt.

---

## Contact

Questions sur la confidentialité : ouvrez une issue sur [github.com/voksed/carnelia-vpn/issues](https://github.com/voksed/carnelia-vpn/issues).
