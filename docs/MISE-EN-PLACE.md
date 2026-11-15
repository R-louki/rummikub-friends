# Mise en place : comptes, amis, partage et mise à jour

Ce que le code fait déjà : connexion Google, pseudo unique, avatar ou photo, amis avec invitations,
historique des parties avec scores, sauvegarde des parties, mise à jour intégrée.
Ce qu'il reste à faire **par vous** (comptes personnels que je ne peux pas créer à votre place) :

## 1. Connexion Google (Google Cloud + Supabase)

1. Sur <https://console.cloud.google.com> : créer un projet « Rummikub Friends ».
2. **Écran de consentement OAuth** : type « Externe », nom de l'application, votre e-mail.
   Tant que l'application est « en test », ajoutez vos amis comme « utilisateurs test »
   (ou cliquez sur « Publier l'application » pour ouvrir à tous : suffisant pour la simple connexion Google).
3. **Identifiants → Créer des identifiants → ID client OAuth** (deux clients) :
   - Type **Application Web** : copiez l'*ID client* et le *code secret client*.
   - Type **Android** : nom de package `com.rummikub.app`, empreinte **SHA-1** :
     `22:4A:4C:64:E7:53:37:27:0F:3B:99:9A:81:1F:14:07:3E:DF:E7:7F` (clé de signature actuelle).
     Si vous publiez sur le Play Store avec « Play App Signing », ajoutez aussi le SHA-1 affiché dans
     la Play Console (Intégrité de l'application), dans un second client Android.
4. Sur <https://supabase.com> → projet `rummikub-friends` → **Authentication → Providers → Google** :
   activer, coller l'ID client Web et le code secret, **Skip nonce checks : activé**, enregistrer.
5. Dans `android/app-config.properties`, renseigner `googleWebClientId=<ID client Web>` puis me dire
   de reconstruire l'application. Tant que cette valeur est vide, l'app joue sans compte comme avant.

## 2. Serveur de jeu (Render)

Service du serveur → **Environment** → ajouter :

| Variable | Valeur |
|---|---|
| `SUPABASE_URL` | `https://wanqpgxhyoybrpbzsjgu.supabase.co` |
| `SUPABASE_SERVICE_KEY` | clé secrète (Supabase → Project Settings → API Keys → `service_role`). **Jamais dans le chat ni dans le dépôt.** |
| `REQUIRE_AUTH` | `1` — à ajouter seulement quand tous les amis ont l'application avec connexion Google. Sans elle, les anciennes versions et les joueurs anonymes sont encore acceptés. |

Chaque modification d'une variable ou chaque push sur GitHub **redémarre le serveur** : les parties
en cours sont alors reprises seulement si les deux premières variables sont déjà en place.

## 3. Lien de téléchargement à envoyer par Messenger

Lien court et stable (page avec les instructions, bouton de téléchargement) :

    https://rummikub-friends.onrender.com/download

Il redirige vers la dernière version publiée sur GitHub :

    https://github.com/R-louki/rummikub-friends/releases/latest/download/Rummikub-Friends.apk

Première publication (une fois) : GitHub → dépôt → **Releases → Draft a new release** → tag `v1.3.0` →
glisser le fichier `Rummikub-Friends.apk` (à la racine du projet) → **Publish release**.

Ensuite, deux options pour les versions suivantes :
- à la main, même procédure avec un tag plus récent ;
- automatique : ajouter dans GitHub → Settings → Secrets → Actions les secrets
  `RUMMIKUB_KEYSTORE_BASE64`, `RUMMIKUB_KEYSTORE_PASSWORD`, `RUMMIKUB_KEY_ALIAS`, `RUMMIKUB_KEY_PASSWORD`,
  puis pousser un tag `v1.3.1` : le workflow `release.yml` construit l'APK signé et le publie.

Chez l'ami : ouvrir le lien dans Chrome → télécharger → ouvrir → autoriser l'installation depuis cette source.
Les mises à jour suivantes se proposent dans l'application (« Mise à jour disponible »).

## 4. Play Store (plus tard, optionnel)

- Compte développeur Google Play : 25 $ une fois, vérification d'identité.
- Fichier à envoyer : `Rummikub-Friends-play.aab` (variante sans auto-installation, exigée par Google).
- Il faut : une politique de confidentialité (URL publique), le formulaire « Sécurité des données »,
  le questionnaire de classification, des captures d'écran. Les comptes personnels récents doivent
  d'abord faire un test fermé avec un certain nombre de testeurs pendant quelques semaines : à vérifier
  dans la console.
- Avec « Play App Signing », Google signe l'application avec sa propre clé : ajouter son SHA-1 dans
  Google Cloud (étape 1.3). Les amis qui ont installé l'APK direct devront désinstaller puis réinstaller
  depuis le Play Store (clés de signature différentes).

## 5. À ne jamais perdre

Dossier `android/keystore/` : la clé qui signe l'application. Sans elle, plus de mise à jour possible
pour les applications déjà installées. Faire une copie dans un endroit sûr (hors de ce PC).
