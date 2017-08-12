/*
 * This file is part of VoxelSniper, licensed under the MIT License (MIT).
 *
 * Copyright (c) The VoxelBox <http://thevoxelbox.com>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.thevoxelbox.voxelsniper.brush.misc;

import com.flowpowered.math.GenericMath;
import com.thevoxelbox.voxelsniper.Message;
import com.thevoxelbox.voxelsniper.SnipeData;
import com.thevoxelbox.voxelsniper.Undo;
import com.thevoxelbox.voxelsniper.brush.Brush;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.data.property.block.SolidCubeProperty;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.util.Color;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Optional;

/**
 * Creates mounds of snow tiles.
 */
public class SnowConeBrush extends Brush {

    private void addSnow(final SnipeData v, Location<World> targetBlock) {
        int brushSize;
        int blockPositionX = targetBlock.getBlockX();
        int blockPositionY = targetBlock.getBlockY();
        int blockPositionZ = targetBlock.getBlockZ();
        if (targetBlock.getBlockType() == BlockTypes.AIR) {
            brushSize = 0;
        }
        else {
            Location<World> sblk = SnowConeBrush.clampY(targetBlock.getExtent(), blockPositionX, blockPositionY, blockPositionZ);
            Optional<Integer> height = sblk.get(Keys.LAYER);
            if (height.isPresent()) {
                brushSize = height.get().intValue() + 1;
            }
            else {
                brushSize = 0;
            }
        }

        final int brushSizeDoubled = 2 * brushSize;
        final BlockState[][] snowcone = new BlockState[brushSizeDoubled + 1][brushSizeDoubled + 1]; // Will hold block IDs
        final int[][] yOffset = new int[brushSizeDoubled + 1][brushSizeDoubled + 1];
        // prime the arrays

        for (int x = 0; x <= brushSizeDoubled; x++) {
            for (int z = 0; z <= brushSizeDoubled; z++) {
                boolean flag = true;
                for (int i = 0; i < 10; i++) { // overlay
                    if (flag) {
                        Location<World> b = targetBlock.add(x - brushSize, -i, z - brushSize);
                        Location<World> bminus1 = targetBlock.add(x - brushSize, -i - 1, z - brushSize);
                        if ((b.getBlockType() == BlockTypes.AIR || b.getBlockType() == BlockTypes.SNOW_LAYER) && 
                                bminus1.getBlockType() != BlockTypes.AIR && bminus1.getBlockType() != BlockTypes.SNOW_LAYER) {
                            flag = false;
                            yOffset[x][z] = i;
                        }
                    }
                }
                snowcone[x][z] = targetBlock.add(x - brushSize, -yOffset[x][z], z - brushSize).getBlock();
            }
        }

        // figure out new snowheights
        for (int x = 0; x <= brushSizeDoubled; x++) {
            final double xSquared = Math.pow(x - brushSize, 2);

            for (int z = 0; z <= 2 * brushSize; z++) {
                final double zSquared = Math.pow(z - brushSize, 2);
                final double dist = Math.pow(xSquared + zSquared, .5); // distance from center of array
                final int snowData = brushSize - (int) Math.ceil(dist);

                if (snowData >= 0) { // no funny business
                    switch (snowData) {
                        case 0:
                            if (snowcone[x][z].getType() == BlockTypes.AIR) {
                                snowcone[x][z] = BlockTypes.SNOW_LAYER.getDefaultState().with(Keys.LAYER, 0).get();
                            }
                            break;
                        case 7: // Turn largest snowtile into snowblock
                            if (snowcone[x][z].getType() == BlockTypes.SNOW_LAYER) {
                                snowcone[x][z] = BlockTypes.SNOW.getDefaultState();
                            }
                            break;
                        default: // Increase snowtile size, if smaller than target
                            if (snowData > snowcone[x][z].get(Keys.LAYER).orElse(0)) {
                                if (snowcone[x][z].getType() == BlockTypes.AIR) {
                                    snowcone[x][z] = BlockTypes.SNOW_LAYER.getDefaultState().with(Keys.LAYER, snowData).get();
                                }
                                else if (snowcone[x][z].getType() == BlockTypes.SNOW_LAYER) {
                                    snowcone[x][z] = snowcone[x][z].with(Keys.LAYER, snowData).get();
                                }
                            }
                            else if (yOffset[x][z] > 0 && snowcone[x][z].getType() == BlockTypes.SNOW_LAYER) {
                                snowcone[x][z] = snowcone[x][z].with(Keys.LAYER, snowcone[x][z].get(Keys.LAYER).orElse(0)+1).get();
                                if (snowcone[x][z].get(Keys.LAYER).orElse(0) == 7) {
                                    snowcone[x][z] = BlockTypes.SNOW.getDefaultState();
                                }
                            }
                            break;
                    }
                }
            }
        }

        this.undo = new Undo(GenericMath.floor((brushSize + 1) * (brushSize + 1)));

        for (int x = 0; x <= brushSizeDoubled; x++) {
            for (int z = 0; z <= brushSizeDoubled; z++) {
                setBlockType(blockPositionX - brushSize + x, blockPositionY - yOffset[x][z], blockPositionZ - brushSize + z, snowcone[x][z].getType());
                setBlockState(blockPositionX - brushSize + x, blockPositionY - yOffset[x][z], blockPositionZ - brushSize + z, snowcone[x][z]);
            }
        }
        v.owner().storeUndo(this.undo);
        this.undo = null;
    }

    private void addSnowOrig(final SnipeData v, Location<World> targetBlock) {
        double brushSize = v.getBrushSize();
        double brushSizeSquared = brushSize * brushSize;

        int tx = targetBlock.getBlockX();
        int tz = targetBlock.getBlockZ();
        int minx = GenericMath.floor(targetBlock.getBlockX() - brushSize);
        int maxx = GenericMath.floor(targetBlock.getBlockX() + brushSize) + 1;
        int miny = Math.max(GenericMath.floor(targetBlock.getBlockY() - brushSize), 0);
        int maxy = Math.min(GenericMath.floor(targetBlock.getBlockY() + brushSize) + 1, WORLD_HEIGHT - 1);
        int minz = GenericMath.floor(targetBlock.getBlockZ() - brushSize);
        int maxz = GenericMath.floor(targetBlock.getBlockZ() + brushSize) + 1;

        this.undo = new Undo(GenericMath.floor((brushSize + 1) * (brushSize + 1)));

        // @Cleanup Should wrap this within a block worker so that it works
        // better with the cause tracker
        for (int x = minx; x <= maxx; x++) {
            double xs = (tx - x) * (tx - x);
            for (int z = minz; z <= maxz; z++) {
                double zs = (tz - z) * (tz - z);
                if (xs + zs < brushSizeSquared) {
                    int y = maxy;
                    boolean topFound = false;
                    for (; y >= miny; y--) {
                        if (this.world.getBlockType(x, y, z) != BlockTypes.AIR) {
                            topFound = true;
                            break;
                        }
                    }
                    if (topFound) {
                        if (y == maxy) {
                            BlockType above = this.world.getBlock(x, y + 1, z).getType();
                            if (above != BlockTypes.AIR) {
                                continue;
                            }
                        }
                        BlockState block = this.world.getBlock(x, y, z);
                        if (block.getType() == BlockTypes.SNOW_LAYER) {
                            Optional<Integer> height = block.get(Keys.LAYER);
                            if (!height.isPresent()) {
                                BlockState newSnow = BlockTypes.SNOW_LAYER.getDefaultState().with(Keys.LAYER, 2).get();
                                setBlockState(x, y, z, newSnow);
                            } else {
                                int sheight = height.get();
                                if (sheight == block.getValue(Keys.LAYER).get().getMaxValue()) {
                                    setBlockType(x, y, z, BlockTypes.SNOW);
                                    setBlockType(x, y + 1, z, BlockTypes.SNOW_LAYER);
                                } else {
                                    BlockState newSnow = BlockTypes.SNOW_LAYER.getDefaultState().with(Keys.LAYER, sheight + 1).get();
                                    setBlockState(x, y, z, newSnow);
                                }
                            }
                        } else if (block.getType() == BlockTypes.WATER || block.getType() == BlockTypes.FLOWING_WATER) {
                            setBlockType(x, y, z, BlockTypes.ICE);
                        } else {
                            Optional<SolidCubeProperty> prop = block.getProperty(SolidCubeProperty.class);
                            if(prop.isPresent() && prop.get().getValue()) {
                                setBlockType(x, y + 1, z, BlockTypes.SNOW_LAYER);
                            }
                        }
                    }
                }
            }
        }

        v.owner().storeUndo(this.undo);
        this.undo = null;
    }

    @Override
    protected final void arrow(final SnipeData v) {
    }

    @Override
    protected final void powder(final SnipeData v) {
        if (this.targetBlock.getBlockType() == BlockTypes.SNOW_LAYER) {
            this.addSnow(v, this.targetBlock);
        }
        else {
            Location<World> blockAbove = this.targetBlock.add(0,1,0);
            if (blockAbove != null && blockAbove.getBlockType() == BlockTypes.AIR) {
                addSnow(v, blockAbove);
            }
            else {
                v.sendMessage(TextColors.RED, "Error: Center block neither snow nor air.");
            }
        }
    }
    
    @Override
    public final void info(final Message vm) {
        vm.brushName("Snow Cone");
    }

    @Override
    public String getPermissionNode() {
        return "voxelsniper.brush.snowcone";
    }
}
