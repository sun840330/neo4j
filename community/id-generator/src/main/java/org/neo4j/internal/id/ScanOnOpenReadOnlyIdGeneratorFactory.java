/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.internal.id;

import java.io.File;
import java.nio.file.OpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.neo4j.io.pagecache.PageCache;

/**
 * {@link IdGeneratorFactory} that ignores the underlying id file and only uses the provided highIdScanner in
 * {@link IdGeneratorFactory#open(PageCache, File, IdType, LongSupplier, long, boolean, OpenOption...)},
 * instantiating {@link IdGenerator} that will return that highId and do nothing else.
 * This is of great convenience when migrating between id file formats.
 */
public class ScanOnOpenReadOnlyIdGeneratorFactory implements IdGeneratorFactory
{
    private final EnumMap<IdType,ReadOnlyHighIdGenerator> idGenerators = new EnumMap<>( IdType.class );

    @Override
    public IdGenerator open( PageCache pageCache, File filename, IdType idType, LongSupplier highIdScanner, long maxId, boolean readOnly,
            OpenOption... openOptions )
    {
        long highId = highIdScanner.getAsLong();
        ReadOnlyHighIdGenerator idGenerator = new ReadOnlyHighIdGenerator( highId );
        idGenerators.put( idType, idGenerator );
        return idGenerator;
    }

    @Override
    public IdGenerator create( PageCache pageCache, File filename, IdType idType, long highId, boolean throwIfFileExists, long maxId,
            boolean readOnly, OpenOption... openOptions )
    {
        return open( pageCache, filename, idType, () -> highId, maxId, readOnly );
    }

    @Override
    public IdGenerator get( IdType idType )
    {
        ReadOnlyHighIdGenerator idGenerator = idGenerators.get( idType );
        if ( idGenerator == null )
        {
            throw new IllegalStateException( "IdGenerator for " + idType + " not opened" );
        }
        return idGenerator;
    }

    @Override
    public void visit( Consumer<IdGenerator> visitor )
    {
        idGenerators.values().forEach( visitor );
    }

    @Override
    public void clearCache()
    {
        idGenerators.values().forEach( IdGenerator::clearCache );
    }

    @Override
    public Collection<File> listIdFiles()
    {
        return Collections.emptyList();
    }
}
