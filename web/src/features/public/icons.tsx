import SvgIcon, { type SvgIconProps } from '@mui/material/SvgIcon';
import AcUnitIcon from '@mui/icons-material/AcUnit';
import BackpackIcon from '@mui/icons-material/Backpack';
import FamilyRestroomIcon from '@mui/icons-material/FamilyRestroom';
import FloodIcon from '@mui/icons-material/Flood';
import LandslideIcon from '@mui/icons-material/Landslide';
import LocalFireDepartmentIcon from '@mui/icons-material/LocalFireDepartment';
import PlaceIcon from '@mui/icons-material/Place';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import type { GuideIconKey, HazardIconKey } from './content';

/** Deprem: ikon kütüphanesinde karşılığı yok; elle yazılmış SVG (sismograf çizgisi üzerinde çatlak ev). */
export function EarthquakeIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props}>
      <path d="M12 3 3 10v2h2v7h5v-4l1.2-1.6L10.4 11l1.8-2 .2 3 1.4 1.6-1 2.4v4h6v-7h2v-2l-9-7Z" />
      <path d="M1 21.5h4l1.5-2 1.5 3 1.5-3 1.5 2h13v1.5H10.3l-.8-1-1.5 3-1.5-3-.9 1H1v-1.5Z" />
    </SvgIcon>
  );
}

export function HazardIcon({ name, ...props }: { name: HazardIconKey } & SvgIconProps) {
  switch (name) {
    case 'earthquake':
      return <EarthquakeIcon {...props} />;
    case 'flood':
      return <FloodIcon {...props} />;
    case 'landslide':
      return <LandslideIcon {...props} />;
    case 'fire':
      return <LocalFireDepartmentIcon {...props} />;
    case 'winter':
      return <AcUnitIcon {...props} />;
    default:
      return <WarningAmberIcon {...props} />;
  }
}

export function GuideIcon({ name, ...props }: { name: GuideIconKey } & SvgIconProps) {
  switch (name) {
    case 'bag':
      return <BackpackIcon {...props} />;
    case 'family':
      return <FamilyRestroomIcon {...props} />;
    default:
      return <PlaceIcon {...props} />;
  }
}
