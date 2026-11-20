package net.knightsandkings.knk.core.production.terminal;

/**
 * {@code POST api/StorageContainers}: bind a block to a storage. {@code containerType} is Chest, TrappedChest or Barrel;
 * a double chest is sent as its left half with {@code isDouble} true.
 */
public record BindRequest(int storageId, StorageContainerInfo.Mode mode, String containerType, boolean isDouble,
                          String worldName, int x, int y, int z, String label) {
}
