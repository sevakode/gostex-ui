"""Guard saved-episode folder regrant wiring in the media library; the shared SAF policy is exercised by
check_torr_folder_access.py, so this check only verifies how MovieLibraryActivity uses it."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/tv/orbit/shield/MovieLibraryActivity.kt'
ui = SOURCE.read_text(encoding='utf-8')


def between(start, end):
    first = ui.index(start)
    return ui[first:ui.index(end, first)]


# The picker callback regrants permission only; it never edits the job, the file or the default folder.
callback = between('private val folderAccessPicker =', 'private fun folderAccessRequestFor(')
assert 'ActivityResultContracts.OpenDocumentTree()' in callback
assert 'TorrDownloadFolderAccess.restore(contentResolver, request, current, uri)' in callback
assert 'TorrDownloadStore.jobs.value' in callback and '.let(::folderAccessRequestFor)' in callback
assert 'TorrDownloadStartup.prepare(' in callback and '!PortableMaintenance.running' in callback
for forbidden in ('TorrDownloadStore.update', 'TorrDownloadActions', 'startTorrDownload', 'TorrDownloadDocuments',
                  'takePersistableUriPermission', 'putString', 'chooseTorrFolder', 'torrDownloadFolder', 'prepareTorrent'):
    assert forbidden not in callback, forbidden
assert 'folderReadGrants = result.readGrants' in callback
assert callback.count('++folderSnapshotGeneration') == 2
assert 'folderAccessReturnFocus = request?.id' in callback and 'finally' in callback
outcomes = {
    'RESTORED': 'R.string.movie_folder_access_restored',
    'CANCELLED': 'R.string.torr_folder_access_cancelled',
    'DIFFERENT_FOLDER': 'R.string.torr_folder_access_different',
    'STALE': 'R.string.movie_folder_access_stale',
    'UNAVAILABLE': 'R.string.torr_folder_access_failed',
}
for outcome, message in outcomes.items():
    assert re.search(rf'Outcome\.{outcome} -> [^\n]*{re.escape(message)}', callback), outcome
assert 'else ->' not in callback  # every outcome is handled explicitly

# The job snapshot matches the shared Request shape and saved state survives process recreation.
assert 'TorrDownloadFolderAccess.Request(job.id, job.tree, job.document, job.state, job.error)' in ui
assert 'arrayListOf(job.id, job.tree, job.document, job.state, job.error)' in ui
assert 'outState.putStringArrayList(STATE_FOLDER_ACCESS_REQUEST, folderAccessRequest)' in ui
assert 'getStringArrayList(STATE_FOLDER_ACCESS_REQUEST)?.takeIf { it.size == 5 }' in ui
assert 'outState.putString(STATE_FOLDER_ACCESS_FOCUS, folderAccessReturnFocus)' in ui

# Completed files need only a read grant; an unknown snapshot (null) is never treated as missing.
assert 'TorrDownloadFolderAccess.needsRestore(job.state, job.error, job.tree, null, folderReadGrants)' in ui
assert ui.count('TorrDownloadFolderAccess.snapshotGrants(') == 1 and 'TorrDownloadFolderAccess.snapshot(' not in ui
assert 'folderReadGrants = snapshot?.read' in ui
resume = between('override fun onResume()', 'override fun onStop()')
assert 'super.onResume()' in resume and 'refreshFolderGrants()' in resume
# The only direct persistable grant remains the existing torrent-file picker.
assert ui.count('takePersistableUriPermission') == 1

restore = between('private fun restoreFolderAccess(', 'private val emulatorInstall')
assert '!needsFolderAccess(job)' in restore and 'folderAccessBusy' in restore and 'folderAccessRequest != null' in restore
assert 'Intent.ACTION_OPEN_DOCUMENT_TREE' in restore and 'frameworkpackagestubs' in restore
assert 'folderAccessPicker.launch(Uri.parse(job.tree))' in restore
assert 'R.string.document_picker_unavailable' in restore
for forbidden in ('TorrDownloadStore.update', 'putString', 'torrDownloadFolder'):
    assert forbidden not in restore, forbidden

# The saved-episode row offers restoration instead of a failing open, and returns focus to the same row.
row = between('items(offlineEpisodes, key = { "offline:${it.id}" })', 'missingTorrentHash?.let')
assert 'val restoreAccess = needsFolderAccess(job)' in row
assert 'produceState<SavedVideoIntegrity.Result?>(null, job, restoreAccess)' in row
# Without the grant the row keeps its streaming fallback; restoring is a separate row below it.
assert 'restoreAccess -> R.string.prefetch_missing' in row
assert 'if (restoreAccess) { restoreFolderAccess(job)' not in row
assert 'prepareTorrent(Uri.parse("torrserver://${job.hash}"), null, false, null, exactFileId = job.index)' in row
assert 'if (restoreAccess) SettingRow(tr(R.string.torr_folder_access_restore), tr(R.string.torr_folder_access_finished_hint)' in row
assert 'onClick = { restoreFolderAccess(job) })' in row
assert 'if (!restoreAccess && job.id == folderAccessReturnFocus) Modifier.focusRequester(offlineFocus)' in row
assert 'modifier = if (job.id == folderAccessReturnFocus) Modifier.focusRequester(offlineFocus) else Modifier' in row
focus = between('val offlineFocus = remember { FocusRequester() }', 'LaunchedEffect(sourceRemovalVersion)')
assert 'offlineFocus.requestFocus()' in focus and 'folderAccessReturnFocus = null' in focus
print('PASS: saved-episode regrant takes the exact recorded tree read-only and never edits the job, file or default folder')
print('PASS: unknown snapshot stays playable, picker request survives recreation, focus returns to the same episode row')

# Every referenced folder-access message exists with EN/ES/RU parity.
used = set(re.findall(r'R\.string\.((?:movie|torr)_folder_access_\w+|document_picker_unavailable)', ui))
new_keys = {'movie_folder_access_restored', 'movie_folder_access_stale'}
assert new_keys <= used
keys = None
for suffix in ('', '-en', '-es', '-ru'):
    folder = ROOT / f'app/src/main/res/values{suffix}'
    entries = {}
    for path in folder.glob('*.xml'):
        for item in ET.parse(path).getroot().findall('string'):
            entries[item.attrib['name']] = (item.text or '').strip()
    missing = used - set(entries)
    assert not missing, (suffix, missing)
    assert all(entries[key] for key in used), suffix
    found = {key for key in entries if key.startswith('movie_folder_access_')}
    assert found == new_keys, (suffix, found)
    if keys is None:
        keys = found
    assert found == keys
print('PASS: movie_folder_access_ and reused torr_folder_access_ strings present in values, values-en, values-es and values-ru')
