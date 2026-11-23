package fr.lulu.unison.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** Identifiant de la version installée par les tâches de profil et de mesure (variantes « release »). */
const val PACKAGE = "fr.lulu.unison"

private const val WAIT_MS = 10_000L

/** Démarrage à froid jusqu'à l'accueil affiché. */
fun MacrobenchmarkScope.startToHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.text("Accueil")), WAIT_MS)
    // Une boîte de dialogue système (notifications) peut couvrir l'écran au premier lancement.
    device.findObject(By.textContains("Autoriser"))?.click()
    device.findObject(By.textContains("Allow"))?.click()
}

/** Défile l'accueil vers le bas puis le haut (sections, carrousels, pochettes). */
fun MacrobenchmarkScope.scrollHome() {
    device.wait(Until.hasObject(By.scrollable(true)), WAIT_MS)
    val list = device.findObject(By.scrollable(true)) ?: return
    list.setGestureMargin(device.displayWidth / 6)
    repeat(3) { list.fling(Direction.DOWN); device.waitForIdle() }
    repeat(3) { list.fling(Direction.UP); device.waitForIdle() }
}

/** Les trois onglets, puis retour à l'accueil. */
fun MacrobenchmarkScope.visitTabs() {
    for (tab in listOf("Recherche", "Bibliothèque", "Accueil")) {
        device.findObject(By.text(tab))?.click()
        device.waitForIdle()
    }
}

/**
 * Ouvre le lecteur depuis le mini-lecteur (glisser vers le haut), passe par les paroles et la file, puis le
 * referme. Ne lance pas de musique : sans titre en cours, il n'y a pas de mini-lecteur et rien ne se passe.
 */
fun MacrobenchmarkScope.openPlayer() {
    if (device.findObject(By.desc("Suivant")) == null) return
    val x = device.displayWidth / 3
    val y = device.displayHeight - device.displayHeight / 6
    device.swipe(x, y, x, y - device.displayHeight / 4, 12)
    if (!device.wait(Until.hasObject(By.desc("Réduire")), WAIT_MS)) return
    for (page in listOf("Paroles", "File", "File")) {
        device.findObject(By.text(page))?.click()
        device.waitForIdle()
    }
    device.findObject(By.desc("Réduire"))?.click()
    device.waitForIdle()
}
