package red.jackf.chesttracker.memory;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.realms.dto.RealmsServer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.ChestTracker;
import red.jackf.chesttracker.compat.AppliedEnergisticsHandler;
import red.jackf.chesttracker.compat.ExpandedStorageHandler;

import java.util.*;
import java.util.stream.Collectors;

import static red.jackf.chesttracker.ChestTracker.id;

@Environment(EnvType.CLIENT)
public abstract class MemoryUtils {
    public static final Identifier ENDER_CHEST_ID = id("ender_chest");
    @Nullable
    private static BlockPos latestPos = null;
    @Nullable
    private static RealmsServer lastRealmsServer = null;

    private static List<Memory> currentlyCheckedMemories = new ArrayList<>();
    private static int currentlyCheckedIndex = 0;
    private static Identifier currentlyCheckedWorldId = null;

    private static boolean ignoreNextMerge = false;
    private static boolean forceNextMerge = false;
    private static boolean wasEnderchest;

    private static boolean expandedStorageFailed = false;

    public static <T extends ScreenHandler> void handleItemsFromScreen(@NotNull HandledScreen<T> screen) {
        if (!ignoreNextMerge) {
            if (validScreenToTrack(screen)) {
                MinecraftClient mc = MinecraftClient.getInstance();
                MemoryDatabase database = MemoryDatabase.getCurrent();
                if (database != null && mc.world != null && latestPos != null) {
                    List<ItemStack> stacks = condenseItems(screen.getScreenHandler().slots.stream().filter(MemoryUtils::isValidSlot).map(Slot::getStack).collect(Collectors.toList()));
                    BlockState state = mc.world.getBlockState(latestPos);
                    if (state.getBlock() == Blocks.ENDER_CHEST) {
                        database.mergeItems(MemoryUtils.ENDER_CHEST_ID, Memory.of(BlockPos.ORIGIN, stacks, null, null), Collections.emptyList());
                    } else {
                        Text title = getTitleFromScreen(screen, mc.world.getBlockEntity(latestPos));
                        Collection<BlockPos> connected = getConnected(mc.world, latestPos);
                        database.mergeItems(mc.world.getRegistryKey().getValue(), Memory.of(latestPos, stacks, title, connected.size() > 0 ? getAveragePos(latestPos, connected) : null), connected);
                    }
                }
                if (ChestTracker.CONFIG.miscOptions.printGuiClassNames)
                    ChestTracker.sendDebugMessage(new LiteralText(screen.getClass().getSimpleName()));
            }
        } else {
            ignoreNextMerge = false;
        }
    }

    public static void ignoreNextMerge() {
        ignoreNextMerge = true;
    }

    public static void setForceNextMerge(boolean forceNextMerge) {
        MemoryUtils.forceNextMerge = forceNextMerge;
    }

    public static boolean shouldForceNextMerge() {
        return forceNextMerge;
    }

    private static boolean isValidSlot(Slot slot) {
        try {
            return !(slot.inventory instanceof PlayerInventory)
                && !AppliedEnergisticsHandler.isAE2Slot(slot)
                && slot.hasStack();
        } catch (Throwable ex) {
            return false;
        }
    }

    public static List<ItemStack> condenseItems(List<ItemStack> list) {
        List<ItemStack> stacks = new ArrayList<>();
        list.forEach(newStack -> {
            boolean exists = false;
            for (ItemStack oldStack : stacks) {
                if (areStacksEquivalent(newStack, oldStack, false)) {
                    oldStack.setCount(oldStack.getCount() + newStack.getCount());
                    exists = true;
                }
            }
            if (!exists) stacks.add(newStack);
        });
        return stacks;
    }

    private static Vec3d getAveragePos(BlockPos basePos, Collection<BlockPos> connected) {
        Vec3d base = Vec3d.of(basePos);
        for (BlockPos pos : connected) {
            base = base.add(Vec3d.of(pos));
        }
        return base.multiply(1f / (1 + connected.size())).subtract(Vec3d.of(basePos));
    }

    public static Collection<BlockPos> getConnected(@NotNull World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            if (state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                boolean left = state.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT;
                switch (state.get(ChestBlock.FACING)) {
                    case NORTH:
                        return Collections.singleton(pos.add(left ? 1 : -1, 0, 0));
                    case SOUTH:
                        return Collections.singleton(pos.add(left ? -1 : 1, 0, 0));
                    case WEST:
                        return Collections.singleton(pos.add(0, 0, left ? -1 : 1));
                    case EAST:
                        return Collections.singleton(pos.add(0, 0, left ? 1 : -1));
                }
            }
        } else if (!expandedStorageFailed && FabricLoader.getInstance().isModLoaded("expandedstorage")) {
            try {
                Collection<BlockPos> result = ExpandedStorageHandler.check(state, world, pos);
                if (!result.isEmpty()) return result;
            } catch (Error ex) {
                ChestTracker.LOGGER.error("Error loading expanded storage compat", ex);
                expandedStorageFailed = true;
            }
        }
        return Collections.emptyList();
    }

    @Nullable
    private static Text getTitleFromScreen(HandledScreen<?> screen, @Nullable BlockEntity blockEntity) {
        Text title = screen.getTitle();
        if (title instanceof TranslatableText) { // Likely the default.
            return null;
        } else if (blockEntity instanceof NamedScreenHandlerFactory) {
            return title;
        } else {
            return null;
        }
    }

    private static <T extends ScreenHandler> boolean validScreenToTrack(HandledScreen<T> screen) {
        return !(screen instanceof AbstractInventoryScreen) && screen != null;
    }

    @Nullable
    public static BlockPos getLatestPos() {
        return latestPos;
    }

    public static void setLatestPos(@Nullable BlockPos latestPos) {
        MemoryUtils.latestPos = latestPos != null ? latestPos.toImmutable() : null;
    }

    public static String getSingleplayerName(LevelStorage.Session session) {
        //return makeFileSafe(session.getDirectoryName());
        return session.getDirectoryName();
    }

    public static String makeFileSafe(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|&]", "_");
    }

    public static boolean areStacksEquivalent(@NotNull ItemStack stack1, @NotNull ItemStack stack2, boolean ignoreNbt) {
        return stack1.getItem() == stack2.getItem()
            && (ignoreNbt
            || (!stack1.hasNbt() && !stack2.hasNbt())
            || Objects.equals(stack1.getNbt(), stack2.getNbt())
        );
    }

    @Nullable
    public static RealmsServer getLastRealmsServer() {
        return lastRealmsServer;
    }

    public static void setLastRealmsServer(@Nullable RealmsServer lastRealmsServer) {
        MemoryUtils.lastRealmsServer = lastRealmsServer;
    }

    public static boolean checkExistsInWorld(Memory memory) {
        return checkExistsInWorld(memory, MinecraftClient.getInstance().world);
    }

    public static boolean checkExistsInWorld(Memory memory, ClientWorld world) {
        BlockPos pos = memory.getPosition();
        if (world != null && pos != null) {
            WorldChunk chunk = world.getWorldChunk(pos);
            return chunk instanceof EmptyChunk || isValidInventoryHolder(chunk.getBlockState(pos).getBlock(), world, pos);
        }
        return true;
    }

    public static boolean isValidInventoryHolder(Block block, World world, BlockPos pos) {
        //if (FabricLoader.getInstance().isModLoaded("universalcomponents")) {
        //return UniversalComponentsHandler.isValidInventoryHolder(block, world, pos);
        //} else {
        return block instanceof BlockEntityProvider || block instanceof InventoryProvider;
        //}
    }

    public static void checkValidCycle(ClientWorld world) {
        if (world.getTime() % ChestTracker.CONFIG.databaseOptions.destroyedMemoryCheckInterval == 0) {
            MemoryDatabase database = MemoryDatabase.getCurrent();
            if (database != null) {
                if (!world.getRegistryKey().getValue().equals(currentlyCheckedWorldId)) {
                    currentlyCheckedWorldId = world.getRegistryKey().getValue();
                    currentlyCheckedMemories.clear();
                    currentlyCheckedIndex = 0;
                }
                if (currentlyCheckedMemories.size() == 0) {
                    //currentlyCheckedMemories = new ArrayList<>(database.getAllMemories(world.getRegistryKey().getValue()));
                    currentlyCheckedMemories = new ArrayList<>();
                    for (Memory memory : database.getAllMemories(world.getRegistryKey().getValue())) {
                        // Creating a new ArrayList from a ConcurrentHashMap.ValuesView can apparently cause a
                        // NegativeArraySizeInspection, so in an attempt to fix this the ArrayList is getting populated
                        // manually.
                        //noinspection UseBulkOperation
                        currentlyCheckedMemories.add(memory);
                    }
                    currentlyCheckedIndex = currentlyCheckedMemories.size() - 1;
                }
                if (currentlyCheckedIndex >= 0) {
                    Memory memory = currentlyCheckedMemories.get(currentlyCheckedIndex);
                    if (memory != null) {
                        if (!checkExistsInWorld(memory, world)) {
                            database.removePos(world.getRegistryKey().getValue(), memory.getPosition());
                        }
                        if (ChestTracker.CONFIG.miscOptions.rememberNewChests && memory.getTitle() == null && memory.getItems().size() == 0) {
                            database.removePos(world.getRegistryKey().getValue(), memory.getPosition());
                        }
                    }
                    currentlyCheckedMemories.remove(currentlyCheckedIndex);
                    currentlyCheckedIndex--;
                }
            }
        }
    }

    public static void setWasEnderchest(boolean wasEnderchest) {
        MemoryUtils.wasEnderchest = wasEnderchest;
    }

    public static boolean wasLastEnderchest() {
        return wasEnderchest;
    }
}
