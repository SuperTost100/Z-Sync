package de.kjell.zencompanion.ui.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddToPhotos
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kjell.zencompanion.R
import de.kjell.zencompanion.data.KeptPage
import de.kjell.zencompanion.sync.ZenSpaces
import de.kjell.zencompanion.ui.components.Favicon
import de.kjell.zencompanion.ui.components.PinDestination
import de.kjell.zencompanion.ui.components.PinDestinationModel
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import de.kjell.zencompanion.ui.theme.liftBorder
import de.kjell.zencompanion.util.Haptics
import de.kjell.zencompanion.util.PageTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(18.dp)

/**
 * "On this phone": the kept pages as a two-column grid of snapshots. Opens
 * from the browser's tabs button and from the count in the home search field.
 * [currentPageId] gets a ring: the page the browser is showing.
 */
@Composable
fun KeptPagesSheet(
    pages: List<KeptPage>,
    currentPageId: String?,
    spaces: List<ZenSpaces.ZenSpace>,
    /** Folders are offered only when the save lands as a pinned tab. */
    pinsIntoFolders: Boolean,
    onOpen: (KeptPage) -> Unit,
    onNewPage: () -> Unit,
    onClose: (KeptPage) -> Unit,
    onCloseAll: () -> Unit,
    onPin: (KeptPage, PinDestination) -> Unit,
    onShare: (KeptPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalZenColors.current
    var confirmCloseAll by remember { mutableStateOf(false) }

    ZenSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.kept_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_done),
                        tint = colors.ink,
                    )
                }
            }

            Box(Modifier.weight(1f)) {
                if (pages.isEmpty()) {
                    EmptyState(Modifier.align(Alignment.Center))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(pages, key = { it.id }) { page ->
                            KeptPageCard(
                                page = page,
                                isCurrent = page.id == currentPageId,
                                spaces = spaces,
                                pinsIntoFolders = pinsIntoFolders,
                                onOpen = { onOpen(page) },
                                onClose = { onClose(page) },
                                onPin = { onPin(page, it) },
                                onShare = { onShare(page) },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pages.isNotEmpty()) {
                    TextButton(onClick = { confirmCloseAll = true }) {
                        Text(
                            stringResource(R.string.kept_close_all),
                            style = ZenType.rounded(16, FontWeight.Medium),
                            color = colors.ink,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNewPage) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = colors.coral, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.kept_new_page),
                        style = ZenType.rounded(16, FontWeight.SemiBold),
                        color = colors.coral,
                    )
                }
            }
        }
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text(stringResource(R.string.kept_close_all_title)) },
            text = { Text(stringResource(R.string.kept_close_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloseAll = false
                    onCloseAll()
                }) {
                    Text(stringResource(R.string.kept_close_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val colors = LocalZenColors.current
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(colors.lift, RoundedCornerShape(20.dp))
                .border(liftBorder(), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AddToPhotos, contentDescription = null, tint = colors.coral, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.kept_empty_title),
            style = ZenType.rounded(21, FontWeight.Medium),
            color = colors.ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.kept_empty_message),
            style = ZenType.rounded(15, FontWeight.Normal),
            color = colors.ink.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeptPageCard(
    page: KeptPage,
    isCurrent: Boolean,
    spaces: List<ZenSpaces.ZenSpace>,
    pinsIntoFolders: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onPin: (PinDestination) -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalZenColors.current
    val context = LocalContext.current
    val title = PageTitle.headline(page.title, page.url)
    val currentLabel = stringResource(R.string.kept_current_page)
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(colors.lift)
                .border(
                    if (isCurrent) androidx.compose.foundation.BorderStroke(2.5.dp, colors.coral) else liftBorder(),
                    CardShape,
                )
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        Haptics.perform(context, Haptics.Kind.SELECTION)
                        menuOpen = true
                    },
                )
                .semantics { if (isCurrent) contentDescription = "$title, $currentLabel" },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Favicon(urlString = page.url, size = 16.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = title,
                    style = ZenType.rounded(13, FontWeight.Medium),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.kept_close_page_named, title),
                        tint = colors.ink.copy(alpha = 0.55f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.ink.copy(alpha = 0.1f)),
            )
            PageShot(page = page)
        }

        KeptPageMenu(
            expanded = menuOpen,
            spaces = spaces,
            pinsIntoFolders = pinsIntoFolders,
            onDismiss = { menuOpen = false },
            onPin = { destination ->
                menuOpen = false
                onPin(destination)
            },
            onCopy = {
                menuOpen = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("URL", page.url))
                Haptics.perform(context, Haptics.Kind.SELECTION)
            },
            onShare = {
                menuOpen = false
                onShare()
            },
            onClose = {
                menuOpen = false
                onClose()
            },
        )
    }
}

/** The page as it looked when it was last left; the favicon until there is one. */
@Composable
private fun PageShot(page: KeptPage) {
    val colors = LocalZenColors.current
    val context = LocalContext.current
    var image by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.id, page.shotAt) {
        if (page.shotAt == 0L) return@LaunchedEffect
        val file = de.kjell.zencompanion.data.KeptPagesStore.shared(context).snapshotFile(page.id)
        image = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        } ?: image
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp)
            .background(colors.paper),
        contentAlignment = Alignment.Center,
    ) {
        val shot = image
        if (shot != null) {
            Image(
                bitmap = shot,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Favicon(urlString = page.url, size = 32.dp)
        }
    }
}

/**
 * Long press on a page. Pin to space turns the menu into the destination
 * list (each space, then its folders), the choice the browser's Pin offers.
 */
@Composable
private fun KeptPageMenu(
    expanded: Boolean,
    spaces: List<ZenSpaces.ZenSpace>,
    pinsIntoFolders: Boolean,
    onDismiss: () -> Unit,
    onPin: (PinDestination) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalZenColors.current
    var choosing by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (choosing) {
            for (space in spaces) {
                DropdownMenuItem(
                    text = { Text(space.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) },
                    onClick = { onPin(PinDestination.of(space)) },
                )
                if (pinsIntoFolders) {
                    for (folder in PinDestinationModel.folderEntries(space)) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    folder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = (16 * (folder.depth + 1)).dp),
                                )
                            },
                            onClick = { onPin(PinDestination(spaceId = space.id, folderId = folder.folderId)) },
                        )
                    }
                }
            }
            return@DropdownMenu
        }
        DropdownMenuItem(
            text = { MenuText(stringResource(R.string.kept_pin_to_space)) },
            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null, tint = colors.ink) },
            enabled = spaces.isNotEmpty(),
            onClick = { choosing = true },
        )
        DropdownMenuItem(
            text = { MenuText(stringResource(R.string.kept_copy_link)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = colors.ink) },
            onClick = onCopy,
        )
        DropdownMenuItem(
            text = { MenuText(stringResource(R.string.browser_share)) },
            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = colors.ink) },
            onClick = onShare,
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.kept_close_page),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            },
            leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = onClose,
        )
    }
}

@Composable
private fun MenuText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}
