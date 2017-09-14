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
import { map } from 'lodash';
import HealthItem from './HealthItem';
import OpenCloseIcon from '../../../../components/icons-components/OpenCloseIcon';
import Section from './Section';
import { HealthType, HealthCause, SysValueObject } from '../../../../api/system';
import { groupSections } from '../../utils';

interface Props {
  biggerHealth?: boolean;
  health: HealthType;
  healthCauses: HealthCause[];
  onClick: (toggledCard: string) => void;
  open: boolean;
  name: string;
  sysInfoData: SysValueObject;
}

export default class HealthCard extends React.PureComponent<Props> {
  handleClick = () => this.props.onClick(this.props.name);

  render() {
    const { open, sysInfoData } = this.props;
    const { mainSection, sections } = groupSections(sysInfoData);
    const showFields = open && mainSection && Object.keys(mainSection).length > 0;
    const showSections = open && sections;
    return (
      <li className="boxed-group system-info-health-card">
        <div
          className={classNames('boxed-group-header', { 'with-bot-padding': !open })}
          onClick={this.handleClick}>
          <OpenCloseIcon className="spacer-right" open={open} />
          <span className="system-info-health-card-title">{this.props.name}</span>
          <HealthItem
            className={classNames('pull-right', { 'big-dot': this.props.biggerHealth })}
            health={this.props.health}
            healthCauses={this.props.healthCauses}
          />
        </div>
        {open && (
          <div className="boxed-group-inner">
            {showFields && <Section items={mainSection} />}
            {showSections &&
              map(sections, (section, name) => <Section key={name} items={section} name={name} />)}
          </div>
        )}
      </li>
    );
  }
}
