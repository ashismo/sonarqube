/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import * as React from 'react';
import * as classNames from 'classnames';
import HealthCauseItem from './HealthCauseItem';
import { HealthType, HealthCause } from '../../../../api/system';

interface Props {
  className?: string;
  health: HealthType;
  healthCauses?: HealthCause[];
}

export default function HealthItem({ className, health, healthCauses }: Props) {
  const hasHealthCauses = healthCauses && healthCauses.length > 0 && health !== HealthType.GREEN;
  return (
    <div className={classNames('system-info-health-info', className)}>
      {hasHealthCauses &&
        healthCauses!.map((cause, idx) => (
          <HealthCauseItem key={idx} className="spacer-right" health={health} healthCause={cause} />
        ))}
      <span className={classNames('system-info-health-dot', health)} />
    </div>
  );
}
