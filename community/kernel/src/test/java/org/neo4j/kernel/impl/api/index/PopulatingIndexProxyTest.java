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
package org.neo4j.kernel.impl.api.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.neo4j.kernel.impl.api.index.MultipleIndexPopulator.IndexPopulation;
import org.neo4j.internal.schema.IndexDescriptor2;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.SchemaDescriptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PopulatingIndexProxyTest
{
    private final IndexDescriptor2 index = IndexPrototype.forSchema( SchemaDescriptor.forLabel( 1, 2 ) ).materialise( 13 );
    private final IndexPopulationJob indexPopulationJob = mock( IndexPopulationJob.class );
    private final IndexPopulation indexPopulation = mock( IndexPopulation.class );
    private PopulatingIndexProxy populatingIndexProxy;

    @BeforeEach
    void setUp()
    {
        populatingIndexProxy = new PopulatingIndexProxy( index, indexPopulationJob, indexPopulation );
    }

    @Test
    void cancelPopulationJobOnClose()
    {
        populatingIndexProxy.close();

        verify( indexPopulationJob ).cancelPopulation( indexPopulation );
    }

    @Test
    void cancelPopulationJobOnDrop()
    {
        populatingIndexProxy.drop();

        verify( indexPopulationJob ).dropPopulation( indexPopulation );
    }
}
