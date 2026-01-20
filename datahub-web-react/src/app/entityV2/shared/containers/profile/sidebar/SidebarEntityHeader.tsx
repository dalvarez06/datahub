import React from 'react';
import styled from 'styled-components';

import RunIngestionButton from '@app/entity/dataset/profile/RunIngestionButton';
import { useEntityData, useRefetch } from '@app/entity/shared/EntityContext';
import { DeprecationIcon } from '@app/entityV2/shared/components/styled/DeprecationIcon';
import EntityTitleLoadingSection from '@app/entityV2/shared/containers/profile/header/EntityHeaderLoadingSection';
import EntityName from '@app/entityV2/shared/containers/profile/header/EntityName';
import PlatformHeaderIcons from '@app/entityV2/shared/containers/profile/header/PlatformContent/PlatformHeaderIcons';
import StructuredPropertyBadge from '@app/entityV2/shared/containers/profile/header/StructuredPropertyBadge';
import { getParentEntities } from '@app/entityV2/shared/containers/profile/header/getParentEntities';
import { getDisplayedEntityType } from '@app/entityV2/shared/containers/profile/header/utils';
import VersioningBadge from '@app/entityV2/shared/versioning/VersioningBadge';
import ContextPath from '@app/previewV2/ContextPath';
import HealthIcon from '@app/previewV2/HealthIcon';
import NotesIcon from '@app/previewV2/NotesIcon';
import HorizontalScroller from '@app/sharedV2/carousel/HorizontalScroller';
import EntitySidebarContext from '@app/sharedV2/EntitySidebarContext';
import PlatformIcon from '@app/sharedV2/icons/PlatformIcon';
import { useEntityRegistry } from '@app/useEntityRegistry';

import { DataPlatform, EntityType, Post } from '@types';

const TitleContainer = styled(HorizontalScroller)`
    display: flex;
    gap: 5px;
`;

const EntityDetailsContainer = styled.div`
    display: flex;
    flex-direction: column;
    gap: 5px;
    margin-left: 8px;
`;

const NameWrapper = styled.div`
    display: flex;
    gap: 6px;
    align-items: center;

    font-size: 16px;
`;

const iconStyles = {
    borderRadius: '16px',
    border: '1px solid #FFF',
    padding: '10px',
};

const SidebarEntityHeader = () => {
    const { urn, entityType, entityData, loading } = useEntityData();
    const refetch = useRefetch();
    const entityRegistry = useEntityRegistry();
    const entityUrl = entityRegistry.getEntityUrl(entityType, entityData?.urn as string);
    const { forLineage } = React.useContext(EntitySidebarContext);

    const displayedEntityType = getDisplayedEntityType(entityData, entityRegistry, entityType);

    const platform = entityType === EntityType.SchemaField ? entityData?.parent?.platform : entityData?.platform;
    const platforms =
        entityType === EntityType.SchemaField ? entityData?.parent?.siblingPlatforms : entityData?.siblingPlatforms;

    const parentEntities = getParentEntities(entityData, entityType);

    if (loading) {
        return <EntityTitleLoadingSection />;
    }
    const showEntityIconFallback = entityType === EntityType.DataJob;
    const showRunIngestion = forLineage && entityType === EntityType.DataJob;

    return (
        <TitleContainer scrollButtonSize={18} scrollButtonOffset={15}>
            {showEntityIconFallback ? (
                <PlatformIcon
                    platform={platform as DataPlatform}
                    size={24}
                    entityType={EntityType.DataJob}
                    styles={iconStyles}
                />
            ) : (
                <PlatformHeaderIcons
                    platform={platform as DataPlatform}
                    platforms={platforms as DataPlatform[]}
                    size={24}
                />
            )}
            <EntityDetailsContainer>
                <NameWrapper>
                    <EntityName isNameEditable={false} />
                    {showRunIngestion && <RunIngestionButton compact />}
                    {!!entityData?.notes?.total && (
                        <NotesIcon notes={entityData?.notes?.relationships?.map((r) => r.entity as Post) || []} />
                    )}
                    {entityData?.deprecation?.deprecated && (
                        <DeprecationIcon
                            urn={urn}
                            deprecation={entityData?.deprecation}
                            showUndeprecate
                            refetch={refetch}
                            showText={false}
                        />
                    )}
                    {entityData?.health && <HealthIcon urn={urn} health={entityData.health} baseUrl={entityUrl} />}
                    <StructuredPropertyBadge structuredProperties={entityData?.structuredProperties} />
                    <VersioningBadge
                        versionProperties={entityData?.versionProperties ?? undefined}
                        showPopover={false}
                    />
                </NameWrapper>
                <ContextPath
                    displayedEntityType={displayedEntityType}
                    entityType={entityType}
                    browsePaths={entityData?.browsePathV2}
                    parentEntities={parentEntities}
                />
            </EntityDetailsContainer>
        </TitleContainer>
    );
};

export default SidebarEntityHeader;
