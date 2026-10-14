# TVPlayer

Lecteur vidéo pour Android TV et Fire TV (Media3 / ExoPlayer), à utiliser comme
lecteur externe dans Wholphin.

- Décalage audio réglable par pas de 50 ms (de -5000 à +5000 ms), mémorisé
- Lecture/pause, avance et retour de 10 s, barre de progression
- Choix de la piste audio et des sous-titres, vitesse de lecture
- Épisode suivant / précédent quand l'application appelante envoie une liste
- Passage automatique à l'élément suivant, retour à Wholphin à la fin de la liste

## Obtenir l'APK sans Android Studio (compilation dans le cloud, gratuite)

1. Crée un compte sur github.com, puis un nouveau dépôt **public** nommé `TVPlayer`.
2. Dans le dépôt : **Add file > Upload files**, puis glisse tout le contenu du dossier
   décompressé (pas le zip). Valide avec **Commit changes**.
3. Vérifie que le fichier `.github/workflows/build.yml` est bien présent. S'il manque
   (les dossiers cachés ne se glissent pas toujours) : **Add file > Create new file**,
   tape le nom `.github/workflows/build.yml` et colle le contenu du fichier `build.yml` fourni.
4. Onglet **Actions** : la compilation démarre toute seule (environ 5 minutes).
5. Quand elle est verte, ouvre la section **Releases** à droite de la page du dépôt :
   `app-debug.apk` y est téléchargeable.

Si la compilation échoue (croix rouge), ouvre l'exécution, copie les dernières lignes
d'erreur et envoie-les-moi : je corrigerai le code.

## Installer sur Fire TV / Android TV

- Fire TV : installe l'appli **Downloader**, active « Applications de sources inconnues »
  pour elle dans les paramètres, puis saisis l'adresse du fichier `app-debug.apk` de la release.
- Android TV : même principe avec Downloader, ou envoie le fichier avec « Send Files to TV ».

À chaque nouvelle compilation, désinstalle d'abord l'ancienne version (la signature
« debug » change d'un build à l'autre).

## Utilisation avec Wholphin

Dans Wholphin : paramètres de lecture, lecteur externe, choisis **TVPlayer**.

## Commandes

| Action | Télécommande |
| --- | --- |
| Afficher les commandes | Bas, Centre ou Menu |
| Avancer / reculer de 10 s (commandes masquées) | Droite / Gauche |
| Épisode suivant / précédent | boutons de la barre, ou touches Média suivant / précédent |
| Régler le décalage audio | Haut (commandes affichées) puis Gauche/Droite + OK, ou touche Menu |
| Pistes audio, sous-titres, vitesse | boutons de la barre de lecture |
| Quitter | Retour (un premier appui masque les commandes) |

Décalage audio : une valeur **positive retarde le son** (à utiliser si le son arrive
avant l'image), une valeur **négative l'avance** (si le son est en retard).

## Limites connues

- Le décalage audio ne fonctionne pas quand le son est envoyé tel quel à un ampli ou
  une barre de son (passthrough Dolby/DTS) : il agit sur le son décodé par l'appareil.
- Suivant/précédent n'existent que si Wholphin envoie la liste des épisodes. Au
  lancement, un message indique combien d'éléments ont été reçus.
