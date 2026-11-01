#!/usr/bin/env python3
"""Renommage général des modules Maven (stdlib uniquement).

À lancer à la RACINE du dépôt (là où se trouve le pom parent) :

    python rename_modules.py --dry-run         # affiche ce qui serait fait
    python rename_modules.py --moves-only     # 1) déplace les dossiers (git mv) -> commit
    python rename_modules.py --edits-only     # 2) réécrit les poms             -> commit
    python rename_modules.py                  # les deux d'un coup (sans git, ou si peu importe l'historique)

Avec git, faire deux commits : un commit de pur renommage est détecté à 100 %
(git log --follow, git blame), alors qu'un déplacement mêlé à des modifications
peut tomber sous le seuil de similarité (50 %) et apparaître comme suppression + ajout.

Le renommage du dossier racine lui-même (quarkus-tchap-bot -> tchap-bot) reste
manuel : un script ne peut pas renommer le répertoire dans lequel il tourne.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path.cwd()
DRY = "--dry-run" in sys.argv
MOVES = "--edits-only" not in sys.argv
EDITS = "--moves-only" not in sys.argv

# Table appliquée en UNE passe : pas de double substitution
# (quarkus-tchap-bot -> quarkus-tchap-bot-parent ne repasse pas dans la table).
ARTIFACT_MAP = {
    "quarkus-tchap-bot-parent": "tchap-bot-parent",         # parent global (libs matrix-*, extension, exemples)
    "quarkus-tchap-bot": "quarkus-tchap-bot-parent",        # agrégateur de l'extension
    "quarkus-tchap-bot-runtime": "quarkus-tchap-bot",       # runtime = nom public de l'extension
    "tchap-bot-integration-tests": "matrix-integration-tests",
    "tchap-bot-example-simple": "tchap-bot-example-simple-bot",
}
NAME_MAP = {
    "Quarkus Tchap Bot Extension - Parent": "Tchap Bot - Parent",
    "Quarkus Tchap Bot Extension": "Quarkus Tchap Bot - Parent",
    "Quarkus Tchap Bot - Functional Matrix Tests": "Matrix - Integration Tests",
}
DIR_MAP = {"integration-tests": "matrix-integration-tests"}

EXT_DEP_FIX = ("examples/event-bot/pom.xml", "examples/command-bot/pom.xml")
EXT_IT_POM = "quarkus-tchap-bot-integration-tests/pom.xml"
EXT_AGGREGATOR_POM = "quarkus-tchap-bot/pom.xml"


def log(msg):
    print(("[dry-run] " if DRY else "") + msg)


def sub_element(tag, mapping, text):
    pattern = re.compile(rf"<{tag}>([^<]*)</{tag}>")
    return pattern.sub(lambda m: f"<{tag}>{mapping.get(m.group(1).strip(), m.group(1))}</{tag}>", text)


def rewrite(rel, text):
    text = sub_element("artifactId", ARTIFACT_MAP, text)
    text = sub_element("name", NAME_MAP, text)
    text = sub_element("module", DIR_MAP, text)

    # Les exemples doivent dépendre du runtime (le deployment est résolu via le descripteur)
    if rel in EXT_DEP_FIX:
        text = text.replace("<artifactId>quarkus-tchap-bot-deployment</artifactId>",
                            "<artifactId>quarkus-tchap-bot</artifactId>")

    # Tests d'intégration de l'extension : dépendance runtime, portée compile (TestApp dans src/main)
    if rel == EXT_IT_POM:
        text = re.sub(
            r"(<artifactId>)quarkus-tchap-bot-deployment(</artifactId>)\s*<scope>test</scope>",
            r"\1quarkus-tchap-bot\2",
            text)

    # dependencyManagement de l'agrégateur : doublon exact de celui du parent racine
    if rel == EXT_AGGREGATOR_POM:
        text = re.sub(r"(\r?\n)\s*<dependencyManagement>.*?</dependencyManagement>(?=\r?\n)", "", text, flags=re.S)
    return text


def move(src, dst):
    log(f"mv {src} -> {dst}")
    if DRY:
        return
    tracked = subprocess.run(["git", "ls-files", "--error-unmatch", str(src)],
                             capture_output=True, cwd=ROOT).returncode == 0
    if tracked:
        subprocess.run(["git", "mv", str(src), str(dst)], check=True, cwd=ROOT)
    else:
        (ROOT / src).rename(ROOT / dst)


def main():
    if not (ROOT / "pom.xml").exists():
        sys.exit("pom.xml introuvable : lancez le script à la racine du dépôt.")
    root_pom = (ROOT / "pom.xml").read_bytes().decode("utf-8")
    if EDITS and "<artifactId>tchap-bot-parent</artifactId>" in root_pom:
        sys.exit("Déjà renommé (parent racine = tchap-bot-parent) : un second passage casserait les poms.")
    if EDITS and "<artifactId>quarkus-tchap-bot-parent</artifactId>" not in root_pom:
        sys.exit("Le pom racine n'a pas l'artifactId attendu (quarkus-tchap-bot-parent) : abandon.")

    for pom in (sorted(ROOT.rglob("pom.xml")) if EDITS else []):
        if "target" in pom.parts or ".git" in pom.parts:
            continue
        rel = pom.relative_to(ROOT).as_posix()
        # Octets <-> str sans conversion de fins de ligne : pas de diff parasite (git blame)
        old = pom.read_bytes().decode("utf-8")
        new = rewrite(rel, old)
        if new != old:
            log(f"edit {rel}")
            if not DRY:
                pom.write_bytes(new.encode("utf-8"))

    for old_dir, new_dir in (DIR_MAP.items() if MOVES else []):
        if (ROOT / old_dir).is_dir():
            move(Path(old_dir), Path(new_dir))


if __name__ == "__main__":
    main()
