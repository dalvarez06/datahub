import { PlayCircleOutlined } from '@ant-design/icons';
import { Button, message, Tooltip } from 'antd';
import React, { useState } from 'react';

import { useEntityData } from '@app/entity/shared/EntityContext';
import { resolveRuntimePath } from '@utils/runtimeBasePath';

import { EntityType } from '@types';

type Props = {
    compact?: boolean;
};

export default function RunIngestionButton({ compact = false }: Props) {
    const { urn, entityType } = useEntityData();
    const [loading, setLoading] = useState(false);

    if (entityType !== EntityType.DataJob) {
        return null;
    }

    const triggerIngestion = async () => {
        if (!urn) {
            message.error('Missing job URN');
            return;
        }

        setLoading(true);
        try {
            const response = await fetch(resolveRuntimePath('/api/ingestion/run'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ jobUrn: urn }),
            });

            const responseBody = await response.json().catch(() => ({}));
            if (!response.ok || responseBody?.status === 'failed') {
                const errorMessage =
                    responseBody?.message ||
                    responseBody?.error ||
                    `Request failed (${response.status || 'unknown'})`;
                message.error(errorMessage);
                return;
            }

            message.success(`Ingestion started (${responseBody?.runId || 'ok'})`);
        } catch (e: any) {
            message.error(e?.message || 'Failed to trigger ingestion');
        } finally {
            setLoading(false);
        }
    };

    if (compact) {
        return (
            <Tooltip title="Run ingestion">
                <Button
                    onClick={triggerIngestion}
                    loading={loading}
                    type="text"
                    size="small"
                    icon={<PlayCircleOutlined />}
                    aria-label="Run ingestion"
                />
            </Tooltip>
        );
    }

    return (
        <Button onClick={triggerIngestion} loading={loading}>
            <PlayCircleOutlined /> Run ingestion
        </Button>
    );
}
