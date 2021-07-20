package io.github.flemmli97.flan.event;

import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import io.github.flemmli97.flan.api.data.IPermissionContainer;
import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.api.permission.ObjectToPermissionMap;
import io.github.flemmli97.flan.api.permission.PermissionRegistry;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.claim.PermHelper;
import io.github.flemmli97.flan.config.ConfigHandler;
import io.github.flemmli97.flan.integration.permissionapi.CommandPermission;
import io.github.flemmli97.flan.mixin.IItemAccessor;
import io.github.flemmli97.flan.player.EnumDisplayType;
import io.github.flemmli97.flan.player.EnumEditMode;
import io.github.flemmli97.flan.player.PlayerClaimData;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Set;

public class ItemInteractEvents {

    public static TypedActionResult<ItemStack> useItem(PlayerEntity p, World world, Hand hand) {
        if (world.isClient || p.isSpectator())
            return TypedActionResult.pass(p.getStackInHand(hand));
        ServerPlayerEntity player = (ServerPlayerEntity) p;
        ItemStack stack = player.getStackInHand(hand);
        if (stack.getItem() == ConfigHandler.config.claimingItem) {
            HitResult ray = player.raycast(64, 0, false);
            if (ray != null && ray.getType() == HitResult.Type.BLOCK) {
                claimLandHandling(player, ((BlockHitResult) ray).getBlockPos());
                return TypedActionResult.success(stack);
            }
            return TypedActionResult.pass(stack);
        }
        if (stack.getItem() == ConfigHandler.config.inspectionItem) {
            HitResult ray = player.raycast(32, 0, false);
            if (ray != null && ray.getType() == HitResult.Type.BLOCK) {
                inspect(player, ((BlockHitResult) ray).getBlockPos());
                return TypedActionResult.success(stack);
            }
            return TypedActionResult.pass(stack);
        }

        ClaimStorage storage = ClaimStorage.get((ServerWorld) world);
        BlockPos pos = player.getBlockPos();
        BlockHitResult hitResult = IItemAccessor.getRaycast(world, player, RaycastContext.FluidHandling.SOURCE_ONLY);
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            pos = new ItemPlacementContext(player, hand, stack, hitResult).getBlockPos();
        }
        IPermissionContainer claim = storage.getForPermissionCheck(pos);
        if (claim == null)
            return TypedActionResult.pass(stack);
        ClaimPermission perm = ObjectToPermissionMap.getFromItem(stack.getItem());
        if (perm != null) {
            boolean success = claim.canInteract(player, perm, pos, true);
            if (success)
                return TypedActionResult.pass(stack);
            if (perm == PermissionRegistry.PLACE) {
                BlockPos update = pos;
                if (stack.getItem() == Items.LILY_PAD) {
                    BlockHitResult upResult = hitResult.withBlockPos(hitResult.getBlockPos().up());
                    update = new ItemPlacementContext(new ItemUsageContext(player, hand, upResult)).getBlockPos();
                }
                player.networkHandler.sendPacket(new BlockUpdateS2CPacket(update, world.getBlockState(update)));
                PlayerClaimData.get(player).addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
                updateHeldItem(player);
            }
            return TypedActionResult.fail(stack);
        }
        return TypedActionResult.pass(stack);
    }

    private static final Set<Item> blackListedItems = Sets.newHashSet(Items.COMPASS, Items.FILLED_MAP, Items.FIREWORK_ROCKET);

    public static ActionResult onItemUseBlock(ItemUsageContext context) {
        //Check for Fakeplayer. Since there is no api for that directly check the class
        if (!(context.getPlayer() instanceof ServerPlayerEntity) || !context.getPlayer().getClass().equals(ServerPlayerEntity.class) || context.getStack().isEmpty())
            return ActionResult.PASS;
        ClaimStorage storage = ClaimStorage.get((ServerWorld) context.getWorld());
        BlockPos placePos = new ItemPlacementContext(context).getBlockPos();
        IPermissionContainer claim = storage.getForPermissionCheck(placePos.add(0, 255, 0));
        if (claim == null)
            return ActionResult.PASS;
        if (blackListedItems.contains(context.getStack().getItem()))
            return ActionResult.PASS;
        boolean actualInClaim = !(claim instanceof Claim) || placePos.getY() >= ((Claim) claim).getDimensions()[4];
        ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
        ClaimPermission perm = ObjectToPermissionMap.getFromItem(context.getStack().getItem());
        if (perm != null) {
            if (claim.canInteract(player, perm, placePos, false))
                return ActionResult.PASS;
            else if (actualInClaim) {
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermissionSimple, Formatting.DARK_RED), true);
                return ActionResult.FAIL;
            }
        }
        if (claim.canInteract(player, PermissionRegistry.PLACE, placePos, false)) {
            if (!actualInClaim && context.getStack().getItem() instanceof BlockItem) {
                ((Claim) claim).extendDownwards(placePos);
            }
            return ActionResult.PASS;
        } else if (actualInClaim) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermissionSimple, Formatting.DARK_RED), true);
            BlockState other = context.getWorld().getBlockState(placePos.up());
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(placePos.up(), other));
            PlayerClaimData.get(player).addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
            updateHeldItem(player);
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    /**
     * -2 == Main inventory update
     */
    private static void updateHeldItem(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, player.inventory.selectedSlot, player.inventory.getMainHandStack()));
        player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 40, player.inventory.getStack(40)));
    }

    private static boolean cantClaimInWorld(ServerWorld world) {
        for (String s : ConfigHandler.config.blacklistedWorlds) {
            if (s.equals(world.getRegistryKey().getValue().toString())) {
                return true;
            }
        }
        return false;
    }

    public static void claimLandHandling(ServerPlayerEntity player, BlockPos target) {
        if (!CommandPermission.perm(player, CommandPermission.claimCreate, false)) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermission, Formatting.DARK_RED), true);
            return;
        }
        if (ConfigHandler.config.worldWhitelist) {
            if (!cantClaimInWorld(player.getServerWorld())) {
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.landClaimDisabledWorld, Formatting.DARK_RED), false);
                return;
            }
        } else if (cantClaimInWorld(player.getServerWorld())) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.landClaimDisabledWorld, Formatting.DARK_RED), false);
            return;
        }
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(target.add(0, 255, 0));
        PlayerClaimData data = PlayerClaimData.get(player);
        if (data.claimCooldown())
            return;
        data.setClaimActionCooldown();
        if (claim != null) {
            if (claim.canInteract(player, PermissionRegistry.EDITCLAIM, target)) {
                if (data.getEditMode() == EnumEditMode.SUBCLAIM) {
                    Claim subClaim = claim.getSubClaim(target);
                    if (subClaim != null && data.currentEdit() == null) {
                        if (subClaim.isCorner(target)) {
                            data.setEditClaim(subClaim, player.getBlockPos().getY());
                            data.setEditingCorner(target);
                            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.resizeClaim, Formatting.GOLD), false);
                        } else {
                            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.cantClaimHere, Formatting.RED), false);
                        }
                        data.addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
                    } else {
                        if (data.currentEdit() != null) {
                            if (!data.editingCorner().equals(target)) {
                                Set<Claim> fl = claim.resizeSubclaim(data.currentEdit(), data.editingCorner(), target);
                                if (!fl.isEmpty()) {
                                    fl.forEach(confl -> data.addDisplayClaim(confl, EnumDisplayType.MAIN, player.getBlockPos().getY()));
                                    player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.conflictOther, Formatting.RED), false);
                                }
                                data.setEditClaim(null, 0);
                                data.setEditingCorner(null);
                            }
                        } else if (data.editingCorner() != null) {
                            if (!data.editingCorner().equals(target)) {
                                Set<Claim> fl = claim.tryCreateSubClaim(data.editingCorner(), target);
                                data.addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
                                if (!fl.isEmpty()) {
                                    fl.forEach(confl -> data.addDisplayClaim(confl, EnumDisplayType.CONFLICT, player.getBlockPos().getY()));
                                    player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.conflictOther, Formatting.RED), false);
                                } else {
                                    player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.subClaimCreateSuccess, Formatting.GOLD), false);
                                }
                                data.setEditingCorner(null);
                            }
                        } else
                            data.setEditingCorner(target);
                    }
                } else {
                    if (claim.isCorner(target)) {
                        data.setEditClaim(claim, player.getBlockPos().getY());
                        data.setEditingCorner(target);
                        player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.resizeClaim, Formatting.GOLD), false);
                    } else if (data.currentEdit() != null) {
                        storage.resizeClaim(data.currentEdit(), data.editingCorner(), target, player);
                        data.setEditClaim(null, 0);
                        data.setEditingCorner(null);
                    } else {
                        data.addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
                        player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.cantClaimHere, Formatting.RED), false);
                    }
                }
            } else {
                data.addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.cantClaimHere, Formatting.RED), false);
            }
        } else if (data.getEditMode() == EnumEditMode.SUBCLAIM) {
            player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.wrongMode, data.getEditMode()), Formatting.RED), false);
        } else {
            if (data.currentEdit() != null) {
                storage.resizeClaim(data.currentEdit(), data.editingCorner(), target, player);
                data.setEditClaim(null, 0);
                data.setEditingCorner(null);
            } else if (data.editingCorner() != null) {
                storage.createClaim(data.editingCorner(), target, player);
                data.setEditingCorner(null);
            } else
                data.setEditingCorner(target);
        }
    }

    public static void inspect(ServerPlayerEntity player, BlockPos target) {
        Claim claim = ClaimStorage.get(player.getServerWorld()).getClaimAt(target);
        PlayerClaimData data = PlayerClaimData.get(player);
        if (data.claimCooldown())
            return;
        data.setClaimActionCooldown();
        if (claim != null) {
            String owner = claim.isAdminClaim() ? "<Admin>" : "<UNKOWN>";
            if (!claim.isAdminClaim()) {
                GameProfile prof = player.getServer().getUserCache().getByUuid(claim.getOwner());
                if (prof != null && prof.getName() != null)
                    owner = prof.getName();
            }
            Text text = PermHelper.simpleColoredText(String.format(ConfigHandler.lang.inspectBlockOwner,
                    owner,
                    target.getX(), target.getY(), target.getZ()), Formatting.GREEN);
            player.sendMessage(text, false);
            data.addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
        } else
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.inspectNoClaim, Formatting.RED), false);
    }
}
