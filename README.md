# MadaGestion

Application Android de gestion et de comptabilité pour PME malgaches. Elle fonctionne hors ligne : les données restent sur le téléphone (Room / SQLite).

![Logo](docs/logo.png)

## Version 0.4.0

| Fonction | Écriture générée |
| --- | --- |
| Transport et frais annexes saisis sur la facture, avec « Qui a payé ces frais ? » (celui qui a payé en est crédité) | Vente, payé par l'entreprise : D 624 / C 53 · Vente, payé par le client : D 624, déduit du D 411 · Achat, facturé par le fournisseur : D 624, ajouté au C 401 · Achat, payé par l'entreprise : D 624 / C 53 |
| Avance d'un client ou à un fournisseur : la part d'un règlement non imputée | Encaissement : D 53 / C 419 · Décaissement : D 409 / C 53 |
| Avance déduite à la validation de la facture (interrupteur « Déduire l'avance ») | Vente : D 411 + D 419 / C 707 · Achat : D 607 / C 401 + C 409 |
| Avance imputée plus tard depuis le règlement | D 419 / C 411, ou D 401 / C 409 |
| Achats de marchandises stockées | D 607 / C 401, et entrée en stock D 311 / C 6037 |
| Avoir avec retour des marchandises : imputé d'office sur sa facture, qui devient « Soldée » | Retour client : D 707 / C 411 et D 311 / C 6037 · Retour fournisseur : D 401 / C 607 et D 6037 / C 311 |

Le solde d'un tiers réunit son compte collectif et ses avances (411 + 419, 401 + 409).
La base passe en version 2 : les données de la 0.3.0 sont conservées (colonnes ajoutées par migration). Le schéma est exporté dans `app/schemas/`.

## Version 0.3.0

| Fonction | Où la trouver |
| --- | --- |
| Situation initiale (bilan d'ouverture : comptes, créances, dettes, stock) | Accueil > Situation initiale, ou Paramètres |
| Frais (transport, manutention…) et ristournes rattachés à un client ou à un fournisseur | Tiers > fiche du tiers > « + Frais » / « + Ristourne » |
| Compte du tiers : facturé, frais, ristournes, déjà réglé, solde exact | Tiers > fiche du tiers |
| Avance d'un client ou à un fournisseur | Fiche du tiers > « Encaisser un règlement ou une avance » |
| Paiement au comptant à la validation d'une vente ou d'un achat | Facture > « Réglé au comptant » |
| Factures et avoirs dans des onglets séparés | Ventes et Achats |
| Export Excel des rapports (balance, résultat, bilan, grand livre, tiers) | Rapports > « Exporter Excel » |
| Logo de l'application | Icône du lanceur |

Fonctions déjà présentes : écritures équilibrées et verrouillées, périodes clôturables, numérotation sans trou, contrepassation, factures et avoirs, règlements imputés, stock au CUMP, 12 contrôles d'intégrité, sauvegarde de la base.

## Organisation du code

```
app/src/main/java/mg/madagestion
├── MainActivity.kt
├── core/            Montants exacts (BigDecimal), identifiants, erreur métier
├── data/
│   ├── model/       Entités Room (tables)
│   ├── dao/         Accès aux tables
│   └── AppDatabase.kt, Converters.kt
├── domain/          Règles métier, sans dépendance à l'interface
│   ├── accounting/  Moteur comptable, plan comptable, situation initiale
│   ├── invoicing/   Factures et avoirs, règlement au comptant
│   ├── payment/     Règlements et imputations
│   ├── party/       Compte des tiers, frais et ristournes
│   ├── stock/       Stock au coût moyen pondéré
│   ├── report/      Rapports, export CSV et Excel
│   └── control/     Contrôles d'intégrité
└── ui/              Écrans Jetpack Compose
    ├── components/  Composants partagés (pages, champs, boutons, dialogues)
    ├── navigation/  Écrans et pile de navigation
    ├── theme/, format/
    └── un dossier par module : dashboard, invoice, payment, party, stock, accounting, report, control, settings
```

Les dépendances vont dans un seul sens : `ui` → `domain` → `data` → `core`.
Toute règle comptable est appliquée dans `domain`, jamais dans un écran.

## Compiler l'APK

- **GitHub** : chaque envoi sur la branche `main` lance la compilation (`.github/workflows/main.yml`). L'APK se télécharge dans l'onglet Actions, artefact `MadaGestion-debug-apk`.
- **Android Studio** : ouvrir le dossier, puis Build > Build APK(s).
- **Terminal** : `gradle assembleDebug`. L'APK est dans `app/build/outputs/apk/debug/`.

Tests unitaires : `gradle testDebugUnitTest`.

Les APK de test sont signés avec la clé `app/debug.keystore`, fournie dans le projet : un nouvel APK s'installe ainsi par-dessus le précédent en conservant les données. Une version destinée au public doit être signée avec une clé privée.

## Base de données

Le schéma n'a pas changé depuis la 0.2.0 (version 1) : une base existante s'ouvre telle quelle.
Au premier lancement de la 0.3.0, les deux nouveaux rôles comptables (frais refacturés aux clients, frais accessoires sur achats) sont ajoutés automatiquement, avec le compte 624 s'il manque.

## Limites connues

- Pas de restauration d'une sauvegarde depuis l'application.
- Frais et ristournes sur tiers enregistrés sans TVA.
- Le bilan est une situation simplifiée (soldes des classes 1 à 5), pas un bilan légal.
- Une situation initiale qui a fait entrer du stock ne s'annule pas en un geste : elle se corrige par ajustements de stock et écriture d'opérations diverses.

Faire valider le paramétrage comptable et le bilan d'ouverture par un expert-comptable avant une tenue réelle.
