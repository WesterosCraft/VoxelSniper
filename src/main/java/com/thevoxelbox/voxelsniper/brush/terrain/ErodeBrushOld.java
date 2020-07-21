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

package com.thevoxelbox.voxelsniper.brush.terrain;

import com.flowpowered.math.vector.Vector3i;

import com.thevoxelbox.voxelsniper.Message;
import com.thevoxelbox.voxelsniper.SnipeData;
import com.thevoxelbox.voxelsniper.Undo;
import com.thevoxelbox.voxelsniper.brush.Brush;

import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ported, documented, and improved from VoxelSniper-5.168.0 source by yvantitov for use on WesterosCraft
 * @author yvantitov
 * @author Piotr
 * @author MikeMatrix
 */
@Brush.BrushInfo(
        name = "ErodeOld",
        aliases = {"eo", "erodeold"},
        permission = "voxelsniper.brush.erode",
        category = Brush.BrushCategory.TERRAIN
)
public class ErodeBrushOld extends Brush {
    /**
     * Wrapper for a Block, used by the erosion algorithm
     */
    private static final class BlockWrapper {
        private final Location<World> block;
        private final BlockType material;

        /**
         * Creates a new BlockWrapper pointing to a specified location
         * @param block The block this BlockWrapper will refer to
         */
        public BlockWrapper(final Location<World> block) {
            this.block = block;
            this.material = block.getBlockType();
        }

        /**
         * Creates a new BlockWrapper pointing at a specified location with a specified BlockType (material)
         * @param block The block this BlockWrapper will refer to
         * @param material The BlockType this BlockWrapper will refer to
         */
        public BlockWrapper(final Location<World> block, final BlockType material) {
            this.block = block;
            this.material = material;
        }

        /**
         * Gets this BlockWrapper's block
         * @return The Location<World> where this BlockWrapper's block is located
         */
        public Location<World> getBlock() {
            return this.block;
        }

        /**
         * Gets this BlockWrapper's BlockType
         * @return The BlockType of the block this BlockWrapper refers to
         */
        public BlockType getMaterial() {
            return this.material;
        }

        /**
         * Returns true if the block this BlockWrapper points to is air
         * @return Whether or not this BlockWrapper points to air
         */
        public boolean isEmpty() {
            return (this.getMaterial() == BlockTypes.AIR);
        }

        /**
         * Returns true if this BlockWrapper refers to a liquid block (Lava or Water)
         * @return Whether or not this BlockWrapper points to a liquid block
         */
        public boolean isLiquid() {
            if (material == BlockTypes.WATER || material == BlockTypes.FLOWING_WATER) {
                return true;
            } else return material == BlockTypes.LAVA || material == BlockTypes.FLOWING_LAVA;
        }
    }

    /**
     * Tracks changes to blocks inflicted using ErodeBrushOld
     */
    private static final class BlockChangeTracker {
        private final Map<Integer, Map<Vector3i, BlockWrapper>> blockChanges;
        private final Map<Vector3i, BlockWrapper> flatChanges;
        private final World world;
        private int nextIterationId = 0;

        /**
         * Creates a new BlockChangeTracker in the context of a given World
         * @param world The World whose blocks this BlockChangeTracker will track
         */
        public BlockChangeTracker(final World world) {
            this.blockChanges = new HashMap<>();
            this.flatChanges = new HashMap<>();
            this.world = world;
        }

        /**
         * Gets a block tracked by this BlockChangeTracker at a certain point in its history
         * @param position The position of the block to look for
         * @param iteration The iteration of the block in question to return
         * @return The BlockWrapper pointing to the block
         */
        public BlockWrapper get(final Vector3i position, final int iteration) {
            BlockWrapper changedBlock = null;
            for (int _i = iteration - 1; _i >= 0; --_i) {
                if (this.blockChanges.containsKey(_i) && this.blockChanges.get(_i).containsKey(position)) {
                    changedBlock = this.blockChanges.get(_i).get(position);
                    return changedBlock;
                }
            }
            if (changedBlock == null) {
                changedBlock = new BlockWrapper(world.getLocation(position));
            }
            return changedBlock;
        }

        /**
         * Gets all the BlockWrappers this BlockChangeTracker contains
         * @return A Collection of all this BlockChangeTracker's BlockWrappers
         */
        public Collection<BlockWrapper> getAll() {
            return this.flatChanges.values();
        }

        /**
         * Iterates this BlockChangeTracker
         * @return The next iteration of changes this BlockChangeTracker refers to
         */
        public int nextIteration() {
            return this.nextIterationId++;
        }

        /**
         * Inserts a new BlockWrapper to be tracked by the BlockChangeTracker
         * @param position The position of the BlockWrapper being inserted
         * @param changedBlock The BlockWrapper pointing to the block in question
         * @param iteration The iteration of the block in question
         */
        public void put(final Vector3i position, final BlockWrapper changedBlock, final int iteration) {
            if (!this.blockChanges.containsKey(iteration)) {
                this.blockChanges.put(iteration, new HashMap<>());
            }
            this.blockChanges.get(iteration).put(position, changedBlock);
            this.flatChanges.put(position, changedBlock);
        }
    }

    /**
     * Represents a preset for the erosion brush
     */
    private static final class ErosionPreset {
        private int erosionFaces;
        private int erosionRecursion;
        private int fillFaces;
        private int fillRecursion;

        /**
         * Creates a new ErosionPreset
         * @param erosionFaces The number of adjacent faces to a block required for that block to be removed
         * @param erosionRecursion The number of times the destructive erosion algorithm will recur
         * @param fillFaces The number of faces exposed to air required for a new block to be placed
         * @param fillRecursion The number of times the constructive erosion algorithm will recur
         */
        public ErosionPreset(final int erosionFaces, final int erosionRecursion, final int fillFaces, final int fillRecursion) {
            this.erosionFaces = erosionFaces;
            this.erosionRecursion = erosionRecursion;
            this.fillFaces = fillFaces;
            this.fillRecursion = fillRecursion;
        }
        public int getErosionFaces() {
            return this.erosionFaces;
        }
        public int getErosionRecursion() {
            return this.erosionRecursion;
        }
        public int getFillFaces() {
            return this.fillFaces;
        }
        public int getFillRecursion() {
            return this.fillRecursion;
        }
        public void setErosionFaces(int erosionFaces) {
            this.erosionFaces = erosionFaces;
        }
        public void setErosionRecursion(int erosionRecursion) {
            this.erosionRecursion = erosionRecursion;
        }
        public void setFillFaces(int fillFaces) {
            this.fillFaces = fillFaces;
        }
        public void setFillRecursion(int fillRecursion) {
            this.fillRecursion = fillRecursion;
        }

        /**
         * Gets an inverted version of this preset
         * @return An inverted ErosionPreset
         */
        public ErosionPreset getInverted() {
            return new ErosionPreset(this.fillFaces, this.fillRecursion, this.erosionFaces, this.erosionRecursion);
        }
    }
    private enum Preset {
        MELT(new ErosionPreset(2, 1, 5, 1)),
        FILL(new ErosionPreset(5, 1, 2, 1)),
        SMOOTH(new ErosionPreset(3, 1, 3, 1)),
        LIFT(new ErosionPreset(6, 0, 1, 1)),
        FLOATCLEAN(new ErosionPreset(6, 1, 6, 1));
        ErosionPreset preset;
        Preset(final ErosionPreset preset) {
            this.preset = preset;
        }
        public ErosionPreset getPreset() {
            return this.preset;
        }
    }
    private static final Vector3i[] FACES_TO_CHECK = {
            new Vector3i(0, 0, 1),
            new Vector3i(0, 0, -1),
            new Vector3i(0, 1, 0),
            new Vector3i(0, -1, 0),
            new Vector3i(1, 0, 0),
            new Vector3i(-1, 0, 0)
    };
    private ErosionPreset currentPreset = new ErosionPreset(0, 1, 0, 1);

    /**
     * Creates a message containing basic information about the brush
     * @param vm The Message this function will populate with relevant information
     */
    @Override
    public void info(Message vm) {
        vm.brushName("ErodeOld");
        vm.size();
        vm.custom(TextColors.AQUA, "Erosion minimum exposed faces set to " + this.currentPreset.getErosionFaces());
        vm.custom(TextColors.BLUE, "Fill minimum adjacent faces set to " + this.currentPreset.getFillFaces());
        vm.custom(TextColors.DARK_AQUA, "Erosion recursion amount set to " + this.currentPreset.getErosionRecursion());
        vm.custom(TextColors.DARK_GREEN, "Fill recursion amount set to " + this.currentPreset.getFillRecursion());
    }

    /**
     * Parses parameters given to the brush and modifies its current settings accordingly
     * @param params An array of parameters to interpret
     * @param v The SnipeData object which is modifying this ErodeBrushOld
     */
    @Override
    public final void parameters(final String[] params, final SnipeData v) {
        for (int i = 0; i < params.length; i++) {
            final String param = params[i];
            try {
                this.currentPreset = Preset.valueOf(param.toUpperCase()).getPreset();
                v.getVoxelMessage().brushMessage("Brush preset set to " + param.toLowerCase());
            } catch (IllegalArgumentException illegalArgumentException) {
                try {
                    if (param.equalsIgnoreCase("info")) {
                        v.sendMessage(TextColors.GOLD, "ErodeOld brush parameters");
                        v.sendMessage(TextColors.AQUA, "e[number] (ex:  e3) Set the minimum number of exposed faces required for a block to be eroded.");
                        v.sendMessage(TextColors.BLUE, "f[number] (ex:  f5) Set the minimum number of adjacent faces required to place a block.");
                        v.sendMessage(TextColors.DARK_AQUA, "re[number] (ex:  re3) Set the number of times the brush will recursively erode.");
                        v.sendMessage(TextColors.DARK_GREEN, "rf[number] (ex:  rf5) Set the number of times the brush will recursively extrude.");
                        this.printPresets(v.getVoxelMessage());
                    } else if (param.startsWith("rf")) {
                        int newRecursionVal = Integer.parseInt(param.replace("rf", ""));
                        if (newRecursionVal > 50) {
                            newRecursionVal = 50;
                            v.sendMessage(TextColors.RED, "Note: Recursion has been capped to 50!");
                        }
                        this.currentPreset.setFillRecursion(Math.max(newRecursionVal, 0));
                        v.sendMessage(TextColors.BLUE, "Fill recursion count set to " + this.currentPreset.getFillRecursion());
                    } else if (param.startsWith("re")) {
                        int newRecursionVal = Integer.parseInt(param.replace("re", ""));
                        if (newRecursionVal > 50) {
                            newRecursionVal = 50;
                            v.sendMessage(TextColors.RED, "Note: Recursion has been capped to 50!");
                        }
                        this.currentPreset.setErosionRecursion(Math.max(newRecursionVal, 0));
                        v.sendMessage(TextColors.AQUA, "Erosion recursion count set to " + this.currentPreset.getErosionRecursion());
                    } else if (param.startsWith("f")) {
                        this.currentPreset.setFillFaces(Math.max(Integer.parseInt(param.replace("f", "")), 0));
                        v.sendMessage(TextColors.BLUE, "Fill minimum adjacent faces set to " + this.currentPreset.getFillFaces());
                    } else if (param.startsWith("e")) {
                        this.currentPreset.setErosionFaces(Math.max(Integer.parseInt(param.replace("e", "")), 0));
                        v.sendMessage(TextColors.AQUA, "Erosion minimum exposed faces set to " + this.currentPreset.getErosionFaces());
                    } else {
                        String[] messages = {
                                "That ain't a preset, bud.",
                                "No dice.",
                                "Try again. Maybe it will be a valid preset next time. Who knows?",
                                "Segmentation fault (core dumped)",
                                v.owner().getPlayer().getName() + " is not in the sudoers file. This incident will be reported.",
                                "bruh moment encountered",
                                "Kernel panic - not syncing: Fatal exception in interrupt"
                        };
                        v.getVoxelMessage().brushMessage(messages[ThreadLocalRandom.current().nextInt(0, messages.length)]);
                        break;
                    }
                } catch(NumberFormatException numberFormatException) {
                    v.getVoxelMessage().brushMessage("Invalid format. See /be eo info for more information.");
                    break;
                }
            }
        }
    }

    /**
     * Returns whether a vector is inside of a sphere with a given position and radius
     * @param vectorIn The vector in question
     * @param vectorCenterOfSphere A vector representing the positon of the sphere
     * @param sphereRadius The radius of the sphere
     * @return Whether or not vectorIn is inside a sphere located at vectorCenterOfSphere with radius sphereRadius
     */
    private boolean isVector3iInSphere(Vector3i vectorIn, Vector3i vectorCenterOfSphere, double sphereRadius) {
        float distance = vectorCenterOfSphere.distance(vectorIn);
        return (distance <= sphereRadius);
    }

    /**
     * The main erosion algorithm
     * @param v The SnipeData that is performing erosion
     * @param erosionPreset The preset whose options are used for the algorithm
     */
    private void erosion(final SnipeData v, final ErosionPreset erosionPreset) {

        final BlockChangeTracker _blockChangeTracker = new BlockChangeTracker(this.targetBlock.getExtent());
        int targetBlockX = this.targetBlock.getBlockX();
        int targetBlockY = this.targetBlock.getBlockY();
        int targetBlockZ = this.targetBlock.getBlockZ();
        final Vector3i _targetBlockVector = new Vector3i(targetBlockX, targetBlockY, targetBlockZ);

        for (int _i = 0; _i < erosionPreset.getErosionRecursion(); ++_i) {
            final int _currentIteration = _blockChangeTracker.nextIteration();
            for (int _x = (int)(this.targetBlock.getBlockX() - v.getBrushSize()); _x <= this.targetBlock.getBlockX() + v.getBrushSize(); ++_x) {
                for (int _z = (int)(this.targetBlock.getBlockZ() - v.getBrushSize()); _z <= this.targetBlock.getBlockZ() + v.getBrushSize(); ++_z) {
                    for (int _y = (int)(this.targetBlock.getBlockY() - v.getBrushSize()); _y <= this.targetBlock.getBlockY() + v.getBrushSize(); ++_y) {
                        final Vector3i _currentPosition = new Vector3i(_x, _y, _z);
                        if (isVector3iInSphere(_currentPosition, _targetBlockVector, v.getBrushSize())) {
                            final BlockWrapper _currentBlock = _blockChangeTracker.get(_currentPosition, _currentIteration);

                            if (_currentBlock.isEmpty() || _currentBlock.isLiquid()) {
                                continue;
                            }

                            int _count = 0;
                            for (final Vector3i _vector : ErodeBrushOld.FACES_TO_CHECK) {
                                final Vector3i _relativePosition = _currentPosition.clone().add(_vector);
                                final BlockWrapper _relativeBlock = _blockChangeTracker.get(_relativePosition, _currentIteration);

                                if (_relativeBlock.isEmpty() || _relativeBlock.isLiquid()) {
                                    _count++;
                                }
                            }
                            if (_count >= erosionPreset.getErosionFaces()) {
                                _blockChangeTracker
                                        .put(_currentPosition, new BlockWrapper(_currentBlock.getBlock(), BlockTypes.AIR), _currentIteration);
                            }
                        }
                    }
                }
            }
        }

        for (int _i = 0; _i < erosionPreset.getFillRecursion(); ++_i) {
            final int _currentIteration = _blockChangeTracker.nextIteration();
            for (int _x = (int)(this.targetBlock.getBlockX() - v.getBrushSize()); _x <= this.targetBlock.getBlockX() + v.getBrushSize(); ++_x) {
                for (int _z = (int)(this.targetBlock.getBlockZ() - v.getBrushSize()); _z <= this.targetBlock.getBlockZ() + v.getBrushSize(); ++_z) {
                    for (int _y = (int)(this.targetBlock.getBlockY() - v.getBrushSize()); _y <= this.targetBlock.getBlockY() + v.getBrushSize(); ++_y) {
                        final Vector3i _currentPosition = new Vector3i(_x, _y, _z);
                        if (isVector3iInSphere(_currentPosition, _targetBlockVector, v.getBrushSize())) {
                            final BlockWrapper _currentBlock = _blockChangeTracker.get(_currentPosition, _currentIteration);

                            if (!(_currentBlock.isEmpty() || _currentBlock.isLiquid())) {
                                continue;
                            }

                            int _count = 0;

                            final Map<BlockWrapper, Integer> _blockCount = new HashMap<>();

                            for (final Vector3i _vector : ErodeBrushOld.FACES_TO_CHECK) {
                                final Vector3i _relativePosition = _currentPosition.clone().add(_vector);
                                final BlockWrapper _relativeBlock = _blockChangeTracker.get(_relativePosition, _currentIteration);

                                if (!(_relativeBlock.isEmpty() || _relativeBlock.isLiquid())) {
                                    _count++;
                                    final BlockWrapper _typeBlock = new BlockWrapper(null, _relativeBlock.getMaterial());
                                    if (_blockCount.containsKey(_typeBlock)) {
                                        _blockCount.put(_typeBlock, _blockCount.get(_typeBlock) + 1);
                                    } else {
                                        _blockCount.put(_typeBlock, 1);
                                    }
                                }
                            }

                            BlockWrapper _currentMaterial = new BlockWrapper(null, BlockTypes.AIR);
                            int _amount = 0;

                            for (final BlockWrapper _wrapper : _blockCount.keySet()) {
                                final Integer _currentCount = _blockCount.get(_wrapper);
                                if (_amount <= _currentCount) {
                                    _currentMaterial = _wrapper;
                                    _amount = _currentCount;
                                }
                            }

                            if (_count >= erosionPreset.getFillFaces()) {
                                _blockChangeTracker.put(_currentPosition,
                                        new BlockWrapper(_currentBlock.getBlock(),
                                                _currentMaterial.getMaterial()),
                                        _currentIteration);
                            }
                        }
                    }
                }
            }
        }

        final Undo undoThis = new Undo(_blockChangeTracker.getAll().size());
        for (final BlockWrapper blockWrapper : _blockChangeTracker.getAll()) {
            // tall grass is extruded as grass blocks
            BlockType blockType = blockWrapper.getMaterial();
            if (blockType == BlockTypes.TALLGRASS) {
                blockType = BlockTypes.GRASS;
            }
            undoThis.put(blockWrapper.getBlock());
            blockWrapper.getBlock().setBlockType(blockType);
        }
        v.owner().storeUndo(undoThis);
    }

    /**
     * Populates a Message with information about available presets for the ErodeBrushOld brush
     * @param vm The Message that will be populated with relevant information
     */
    private void printPresets(final Message vm) {
        String printout = "";
        boolean _delimiterHelper = true;
        for (final Preset _treeType : Preset.values()) {
            if (_delimiterHelper) {
                _delimiterHelper = false;
            } else {
                printout += ", ";
            }
            printout += _treeType.name().toLowerCase();
        }
        vm.custom(TextColors.YELLOW, "Available presets: " + printout);
    }

    /**
     * Perform erosion with a given SnipeData
     * @param v The SnipeData that is performing erosion
     */
    @Override
    protected final void arrow(final SnipeData v) {
        this.erosion(v, this.currentPreset);
    }

    /**
     * Perform inverted erosion with a given SnipeData
     * @param v The SnipeData that is performing inverted erosion
     */
    @Override
    protected final void powder(final SnipeData v) {
        this.erosion(v, this.currentPreset.getInverted());
    }
}
