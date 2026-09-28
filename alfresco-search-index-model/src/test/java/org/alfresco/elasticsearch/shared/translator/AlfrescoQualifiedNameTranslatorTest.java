/*-
 * #%L
 * Alfresco Search Community :: Search Index Model
 * %%
 * Copyright (C) 2026 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.elasticsearch.shared.translator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The naming convention for the tests is: MethodName_StateUnderTest_ExpectedBehavior */
public class AlfrescoQualifiedNameTranslatorTest
{
    static final String FULLY_QUALIFIED_NAME = "{http://www.alfresco.org/model/content/1.0}content";
    static final String ENCODED_FULLY_QUALIFIED_NAME = "%7Bhttp%3A%2F%2Fwww%2Ealfresco%2Eorg%2Fmodel%2Fcontent%2F1%2E0%7Dcontent";

    static final String PREFIXED_QNAME = "cm:localName_with&special.characters";
    static final String ENCODED_PREFIXED_QNAME = "cm%3AlocalName_with%26special%2Echaracters";

    @Test
    public void encode_prefixShortQName_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME);
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME);
    }

    @Test
    public void encode_prefixShortQNameWithSpace_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME + "white space");
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME + "white%20space");
    }

    @Test
    public void encode_prefixShortQNameWithStar_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME + "with*");
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME + "with%2A");
    }

    @Test
    public void encode_prefixShortQNameWithMinus_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME + "with-");
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME + "with%2D");
    }

    @Test
    public void encode_prefixShortQNameWithPlus_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME + "with+");
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME + "with%2B");
    }

    @Test
    public void encode_prefixShortQNameWithTilde_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME + "with~");
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME + "with%7E");
    }

    @Test
    public void encode_prefixShortQNameWithPercentage_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(PREFIXED_QNAME + "with%7E");
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_PREFIXED_QNAME + "with%257E");
    }

    @Test
    public void encode_fullyQualifiedName_shouldEncodeSpecialCharacters()
    {
        String elasticsearchFieldName = AlfrescoQualifiedNameTranslator.encode(FULLY_QUALIFIED_NAME);
        assertThat(elasticsearchFieldName).isEqualTo(ENCODED_FULLY_QUALIFIED_NAME);
    }

    @Test
    public void decode_elasticsearchFieldNameFromPrefix_shouldDecodeSpecialCharacters()
    {
        String prefixedQualifiedName = AlfrescoQualifiedNameTranslator.decode(ENCODED_PREFIXED_QNAME);
        assertThat(prefixedQualifiedName).isEqualTo(PREFIXED_QNAME);
    }

    @Test
    public void decode_elasticsearchFieldNameFromPrefixWithWhiteSpace_shouldDecodeSpecialCharacters()
    {
        String prefixedQualifiedName = AlfrescoQualifiedNameTranslator.decode(ENCODED_PREFIXED_QNAME + "white%20space");
        assertThat(prefixedQualifiedName).isEqualTo(PREFIXED_QNAME + "white space");
    }

    @Test
    public void decode_elasticsearchFieldNameFromPrefixWithStar_shouldDecodeSpecialCharacters()
    {
        String prefixedQualifiedName = AlfrescoQualifiedNameTranslator.decode(ENCODED_PREFIXED_QNAME + "with%2A");
        assertThat(prefixedQualifiedName).isEqualTo(PREFIXED_QNAME + "with*");
    }

    @Test
    public void decode_elasticsearchFieldNameFromPrefixWithMinus_shouldDecodeSpecialCharacters()
    {
        String prefixedQualifiedName = AlfrescoQualifiedNameTranslator.decode(ENCODED_PREFIXED_QNAME + "with%2D");
        assertThat(prefixedQualifiedName).isEqualTo(PREFIXED_QNAME + "with-");
    }

    @Test
    public void decode_elasticsearchFieldNameFromPrefixWithPlus_shouldDecodeSpecialCharacters()
    {
        String prefixedQualifiedName = AlfrescoQualifiedNameTranslator.decode(ENCODED_PREFIXED_QNAME + "with%2B");
        assertThat(prefixedQualifiedName).isEqualTo(PREFIXED_QNAME + "with+");
    }

    @Test
    public void decode_elasticsearchFieldNameFromfullyQualifiedName_shouldDecodeSpecialCharacters()
    {
        String fullyQualifiedName = AlfrescoQualifiedNameTranslator.decode(ENCODED_FULLY_QUALIFIED_NAME);
        assertThat(fullyQualifiedName).isEqualTo(FULLY_QUALIFIED_NAME);
    }
}
