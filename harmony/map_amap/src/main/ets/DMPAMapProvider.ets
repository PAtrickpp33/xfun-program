import { UIContext } from '@kit.ArkUI'
import { ArrayList } from '@kit.ArkTS'
import { common, abilityAccessCtrl, Permissions } from '@kit.AbilityKit'
import { DMPNativeMapProvider, DMPNativeMapHandle, DMPNativeMapEvents, DMPMap } from '@xfun/sdk'
import { AMapPrivacyAgreeStatus, AMapPrivacyInfoStatus, AMapPrivacyShowStatus } from '@amap/amap_lbs_common'
import { AMap, MapsInitializer, MapView, MapViewComponent, MapViewManager, MapViewCreateCallback,
  CameraUpdateFactory, CameraPosition, OnCameraChangeListener, LatLng, LatLngBounds, Marker, MarkerOptions, Point, Polyline, PolylineOptions, PolygonOptions, CircleOptions } from '@amap/amap_lbs_map3d'

export class DMPAMapProvider implements DMPNativeMapProvider {
  private context: common.UIAbilityContext
  private apiKey: string
  private hasPrivacyConsent: () => boolean
  constructor(context: common.UIAbilityContext, apiKey: string, hasPrivacyConsent: () => boolean) {
    this.context = context; this.apiKey = apiKey; this.hasPrivacyConsent = hasPrivacyConsent
  }
  create(params: DMPMap, events: DMPNativeMapEvents): DMPNativeMapHandle {
    if (!this.hasPrivacyConsent()) throw new Error('Map privacy authorization denied')
    if (!this.apiKey) throw new Error('AMap HarmonyOS key is required')
    MapsInitializer.updatePrivacyShow(AMapPrivacyShowStatus.DidShow, AMapPrivacyInfoStatus.DidContain, this.context)
    MapsInitializer.updatePrivacyAgree(AMapPrivacyAgreeStatus.DidAgree, this.context)
    MapsInitializer.setApiKey(this.apiKey)
    MapsInitializer.initialize(this.context)
    return new AMapHandle(params, events, this.context)
  }
}
@Builder
function mapBuilder(params: DMPMap) {
  AMapSurface({ params: params })
}
@Component
struct AMapSurface {
  params: DMPMap = new DMPMap()
  build() {
    MapViewComponent({ mapViewName: this.params.getString('mapViewName') ?? '' }).width('100%').height('100%')
      .onAreaChange((_oldArea: Area, area: Area) => {
        const context = this.getUIContext()
        const resize = this.params.get('onResize') as (width: number, height: number, context: UIContext) => void
        resize(context.vp2px(Number(area.width)), context.vp2px(Number(area.height)), context)
      })
  }
}
class AMapHandle implements DMPNativeMapHandle {
  builder: WrappedBuilder<[DMPMap]> = wrapBuilder(mapBuilder)
  builderParams: DMPMap
  private name: string
  private view?: MapView
  private map?: AMap
  private destroyed: boolean = false
  private props: DMPMap
  private previous: DMPMap = new DMPMap()
  private markers: Map<number, Marker> = new Map()
  private markerValues: Map<number, Object> = new Map()
  private anonymousMarkers: Marker[] = []
  private anonymousMarkerValues: Object[] = []
  private arcs: Map<number, Polyline> = new Map()
  private arcValues: Map<number, DMPMap> = new Map()
  private geometry: Map<string, (() => void)[]> = new Map()
  private motions: Map<number, () => void> = new Map()
  private uiContext?: UIContext
  private mapWidth: number = 0
  private mapHeight: number = 0
  private centerOffset: number[] = [0.5, 0.5]
  private boundary?: LatLngBounds
  private lastCamera?: CameraPosition
  private initialized: boolean = false
  private createdCallback: MapViewCreateCallback
  private locationGeneration: number = 0
  private pendingLocation?: (point: LatLng) => void

  private events: DMPNativeMapEvents
  private context: common.UIAbilityContext
  private rejectLocation?: (error: Error) => void
  constructor(params: DMPMap, events: DMPNativeMapEvents, context: common.UIAbilityContext) {
    this.events = events; this.context = context
    this.name = params.getString('id') ?? ''
    this.props = new DMPMap(params.get('props'))
    this.builderParams = new DMPMap({ mapViewName: this.name, onResize: (width: number, height: number, context: UIContext): void => {
      this.uiContext = context
      this.mapWidth = width; this.mapHeight = height
      this.applyCenterOffset()
    } })
    this.createdCallback = (view?: MapView, name?: string): void => {
      if (name !== this.name || !view || this.destroyed) return
      if (this.view) this.detach()
      this.view = view
      view.onCreate()
      view.onResume()
      view.getMapAsync((map: AMap) => {
        if (this.destroyed || this.view !== view) return
        this.map = map
        this.bind(map)
        try {
          const markers = Array.from(this.markerValues.values()).concat(this.anonymousMarkerValues)
          this.previous = new DMPMap()
          this.update(this.props)
          if (this.initialized) {
            if (this.lastCamera) map.moveCamera(CameraUpdateFactory.newCameraPosition(this.lastCamera))
            this.addMarkers(markers, true)
          }
          this.applyCenterOffset()
          if (this.boundary) map.setMapStatusLimits(this.boundary)
          this.arcValues.forEach((value: DMPMap) => this.addArc(value))
          this.initialized = true
          // getMapAsync 已回调地图就绪；此时再监听同一个 ready 事件会错过首次通知。
          this.events.ready()
        } catch (error) { this.events.error((error as Error).message) }
      })
    }
    MapViewManager.getInstance().registerMapViewCreatedCallback(this.createdCallback)
  }
  private bind(map: AMap): void {
    map.setOnMapClickListener((point: LatLng) => this.events.event('tap', coordinate(point)))
    map.setOnMarkerClickListener((marker: Marker): boolean => { this.markerEvent('markertap', marker); return false })
    map.setOnInfoWindowClickListener((marker: Marker) => this.markerEvent('callouttap', marker))
    let changing = false
    map.setOnCameraChangeListener(new OnCameraChangeListener(
      (position: CameraPosition) => { if (!changing) { changing = true; this.region('begin', position) } },
      (position: CameraPosition) => { changing = false; this.region('end', position) }))
    map.setOnMyLocationChangeListener(location => {
      if (this.destroyed) return
      const callback = this.pendingLocation
      this.pendingLocation = undefined
      this.rejectLocation = undefined
      callback?.(new LatLng(location.latitude, location.longitude))
      if (!this.props.getBoolean('showLocation')) map.setMyLocationEnabled(false)
    })
  }
  private markerEvent(type: string, marker: Marker): void {
    this.markers.forEach((value: Marker, id: number) => {
      if (value === marker) this.events.event(type, new DMPMap({ markerId: id }))
    })
    if (this.anonymousMarkers.includes(marker)) this.events.event(type, new DMPMap())
  }
  private region(type: string, position: CameraPosition): void {
    this.events.event('regionchange', new DMPMap({ type: type, causedBy: 'update', centerLocation: coordinate(position.target).toJSON(), scale: position.zoom }))
  }
  update(props: DMPMap): void {
    if (this.destroyed) throw new Error('Map destroyed')
    this.props = props
    const map = this.map
    const changed = (key: string): boolean => JSON.stringify(props.get(key)) !== JSON.stringify(this.previous.get(key))
    if (!map) {
      if (changed('markers')) this.addMarkers(props.getArray<Object>('markers') ?? [], true)
      if (this.lastCamera) {
        this.lastCamera = new CameraPosition(
          changed('longitude') || changed('latitude') ? point(props) : this.lastCamera.target,
          changed('scale') ? props.getNumber('scale') ?? 16 : this.lastCamera.zoom,
          this.lastCamera.tilt, this.lastCamera.bearing)
      }
      this.previous = new DMPMap(JSON.parse(JSON.stringify(props.toJSON())))
      return
    }
    if (changed('longitude') || changed('latitude')) map.moveCamera(CameraUpdateFactory.newLatLng(point(props)))
    if (changed('minScale')) map.setMinZoomLevel(props.getNumber('minScale') ?? 3)
    if (changed('maxScale')) map.setMaxZoomLevel(props.getNumber('maxScale') ?? 22)
    if (changed('rotate') || changed('skew')) {
      const camera = map.getCameraPosition()
      if (camera) map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition(camera.target, camera.zoom,
        props.getNumber('skew') ?? 0, props.getNumber('rotate') ?? 0)))
    }
    if (changed('scale')) map.moveCamera(CameraUpdateFactory.zoomTo(props.getNumber('scale') ?? 16))
    const settings = map.getUiSettings()
    settings?.setScrollGesturesEnabled(props.getBoolean('enableScroll') ?? true)
    settings?.setZoomGesturesEnabled(props.getBoolean('enableZoom') ?? true)
    settings?.setRotateGesturesEnabled(props.getBoolean('enableRotate') ?? false)
    settings?.setScaleControlsEnabled(props.getBoolean('showScale') ?? false)
    settings?.setCompassEnabled(props.getBoolean('showCompass') ?? false)
    settings?.setZoomControlsEnabled(false)
    settings?.setTiltGesturesEnabled(props.getBoolean('enableOverlooking') ?? false)
    for (const kind of ['polyline', 'polygons', 'circles']) {
      if (changed(kind)) this.replaceGeometry(kind, props.getArray<Object>(kind) ?? [])
    }
    if (changed('markers')) this.addMarkers(props.getArray<Object>('markers') ?? [], true)
    if (changed('includePoints')) {
      const points = props.getArray<Object>('includePoints') ?? []
      if (points.length > 0) this.fit(points, [])
    }
    if (changed('showLocation')) {
      const generation = ++this.locationGeneration
      if (props.getBoolean('showLocation')) this.authorizeLocation().then(allowed => {
        if (this.destroyed || generation !== this.locationGeneration) return
        if (allowed) map.setMyLocationEnabled(true)
        else this.events.error('Location permission denied')
      }).catch(() => this.events.error('Location permission request failed'))
      else map.setMyLocationEnabled(false)
    }
    this.previous = new DMPMap(JSON.parse(JSON.stringify(props.toJSON())))
  }
  private async authorizeLocation(): Promise<boolean> {
    const permissions: Permissions[] = ['ohos.permission.APPROXIMATELY_LOCATION', 'ohos.permission.LOCATION']
    const result = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(this.context, permissions)
    return result.authResults.some(value => value === 0)
  }
  async invoke(command: string, args: DMPMap): Promise<DMPMap> {
    const map = this.map
    if (!map || this.destroyed) throw new Error('Map is not ready')
    switch (command) {
      case 'getCenterLocation': {
        const position = map.getCameraPosition()
        if (!position) throw new Error('Map camera is not ready')
        return coordinate(position.target)
      }
      case 'getRotate': return new DMPMap({ rotate: map.getCameraPosition()?.bearing })
      case 'getSkew': return new DMPMap({ skew: map.getCameraPosition()?.tilt })
      case 'setCenterOffset': {
        const offset = args.getArray<number>('offset') ?? []
        if (offset.length !== 2 || offset.some(value => !Number.isFinite(value) || value < 0.25 || value > 0.75)) throw new Error('Invalid offset')
        this.centerOffset = offset; this.applyCenterOffset(); break
      }
      case 'toScreenLocation': {
        const pixel = map.getProjection()?.toScreenLocationSync(point(args))
        if (!pixel || this.mapWidth <= 0 || this.mapHeight <= 0) throw new Error('Map has no size')
        const viewport = new DMPMap(args.get('viewport'))
        return new DMPMap({ x: pixel.x * (viewport.getNumber('width') ?? 0) / this.mapWidth,
          y: pixel.y * (viewport.getNumber('height') ?? 0) / this.mapHeight })
      }
      case 'fromScreenLocation': {
        const viewport = new DMPMap(args.get('viewport'))
        const width = viewport.getNumber('width') ?? 0; const height = viewport.getNumber('height') ?? 0
        if (width <= 0 || height <= 0) throw new Error('Map has no size')
        const target = map.getProjection()?.fromScreenLocationSync(new Point(
          (args.getNumber('x') ?? 0) * this.mapWidth / width, (args.getNumber('y') ?? 0) * this.mapHeight / height))
        if (!target) throw new Error('Map projection unavailable')
        return coordinate(target)
      }
      case 'setBoundary': {
        const bounds = new LatLngBounds(point(new DMPMap(args.get('southwest'))), point(new DMPMap(args.get('northeast'))))
        map.setMapStatusLimits(bounds); this.boundary = bounds; break
      }
      case 'translateMarker':
      case 'moveAlong': await this.animateMarker(command, args); break
      case 'addArc': this.addArc(args); break
      case 'removeArc': {
        const id = args.getNumber('id')!
        this.arcs.get(id)?.remove(); this.arcs.delete(id); this.arcValues.delete(id); break
      }
      case 'getScale': return new DMPMap({ scale: map.getCameraPosition()?.zoom })
      case 'getRegion': {
        const bounds = map.getProjection()?.getVisibleRegion()?.latLngBounds
        if (!bounds) throw new Error('Map bounds are not ready')
        return new DMPMap({ southwest: coordinate(bounds.southwest).toJSON(), northeast: coordinate(bounds.northeast).toJSON() })
      }
      case 'moveToLocation': {
        let target: LatLng
        if (args.get('longitude') !== undefined || args.get('latitude') !== undefined) target = point(args)
        else {
          if (!await this.authorizeLocation()) throw new Error('Location permission denied')
          if (this.destroyed) throw new Error('Map destroyed')
          target = await new Promise<LatLng>((resolve, reject) => { this.pendingLocation = resolve; this.rejectLocation = reject; map.setMyLocationEnabled(true) })
        }
        if (this.destroyed) throw new Error('Map destroyed')
        map.moveCamera(CameraUpdateFactory.newLatLng(target))
        break
      }
      case 'addMarkers': {
        const markers = args.getArray<Object>('markers')
        if (!markers) throw new Error('Markers must be an array')
        this.addMarkers(markers, args.getBoolean('clear') ?? false)
        break
      }
      case 'removeMarkers': {
        const ids = args.getArray<number>('markerIds')
        if (!ids) throw new Error('markerIds must be an array')
        ids.forEach(id => { this.cancelMotion(id); this.markers.get(id)?.remove(); this.markers.delete(id); this.markerValues.delete(id) })
        break
      }
      case 'includePoints': this.fit(args.getArray<Object>('points') ?? [], args.getArray<number>('padding') ?? []); break
      default: throw new Error(`AMap HarmonyOS provider does not support ${command}`)
    }
    return new DMPMap()
  }
  private pixels(value: number): number {
    return this.uiContext?.vp2px(value) ?? value
  }
  private applyCenterOffset(): void {
    if (this.mapWidth > 0 && this.mapHeight > 0) this.map?.setPointToCenter(this.mapWidth * this.centerOffset[0], this.mapHeight * this.centerOffset[1])
  }
  private cancelMotion(id: number): void {
    const cancel = this.motions.get(id)
    this.motions.delete(id)
    cancel?.()
  }
  private async animateMarker(command: string, args: DMPMap): Promise<void> {
    const id = args.getNumber('markerId')!
    const marker = this.markers.get(id)
    if (!marker) throw new Error('Marker not found')
    const path: LatLng[] = command === 'translateMarker' ? [marker.getPosition(), point(new DMPMap(args.get('destination')))] :
      (args.getArray<Object>('path') ?? []).map(value => point(new DMPMap(value)))
    const separate = command === 'translateMarker' && !args.getBoolean('moveWithRotate') && !args.getBoolean('autoRotate')
    const duration = (args.getNumber('duration') ?? 1000) * (separate ? 2 : 1)
    if (path.length < 2 || !Number.isFinite(duration) || duration < 0) throw new Error('Invalid animation')
    const motion = new MarkerMotion(path, -marker.getRotateAngle(), args.getNumber('rotate') ?? -marker.getRotateAngle(),
      args.getBoolean('autoRotate') ?? false, separate)
    this.cancelMotion(id)
    let lastDistance = 0
    let emittedComplete = false
    const precision = args.getNumber('precision') ?? 0
    const apply = (progress: number): void => {
      const sample = motion.sample(progress)
      marker.setPosition(sample.position); marker.setRotateAngle(-sample.rotate)
      if (precision > 0 && !emittedComplete && (progress === 1 || sample.distance - lastDistance >= precision)) {
        emittedComplete = progress === 1
        lastDistance = sample.distance
        this.events.event('interpolatepoint', new DMPMap({ markerId: id, longitude: sample.position.longitude, latitude: sample.position.latitude, animationStatus: progress === 1 ? 'complete' : 'interpolating' }))
      }
    }
    const save = (): void => {
      const value = new DMPMap(this.markerValues.get(id))
      const position = marker.getPosition()
      value.set('longitude', position.longitude); value.set('latitude', position.latitude); value.set('rotate', -marker.getRotateAngle())
      this.markerValues.set(id, value.toJSON())
    }
    if (duration === 0) { apply(1); save(); return }
    const context = this.uiContext
    if (!context) throw new Error('Map UI context is not ready')
    await new Promise<void>((resolve, reject) => {
      const animator = context.createAnimator({ duration: duration, easing: 'linear', delay: 0, fill: 'forwards', direction: 'normal', iterations: 1, begin: 0, end: 1 })
      let settled = false
      animator.onFrame = (progress: number): void => { if (!settled && !this.destroyed) apply(progress) }
      animator.onFinish = (): void => {
        if (settled) return
        settled = true; this.motions.delete(id); apply(1); save(); resolve()
      }
      const cancel = (): void => {
        if (settled) return
        settled = true; save(); animator.cancel(); reject(new Error('Marker animation cancelled'))
      }
      animator.onCancel = cancel
      this.motions.set(id, cancel); apply(0); animator.play()
    })
  }
  private addArc(args: DMPMap): void {
    const id = args.getNumber('id')!
    const points = (args.getArray<Object>('arcPoints') ?? []).map(value => point(new DMPMap(value)))
    if (!Number.isInteger(id) || points.length < 2) throw new Error('Invalid arc')
    const list = new ArrayList<LatLng>(); points.forEach(value => list.add(value))
    const overlay = this.map?.addPolyline(new PolylineOptions().addAll(list).setColor(mapColor(args.getString('color') ?? '#000000')).setWidth(this.pixels(args.getNumber('width') ?? 5)))
    if (!overlay) throw new Error('Could not create arc')
    this.arcs.get(id)?.remove(); this.arcs.set(id, overlay); this.arcValues.set(id, args)
  }
  private replaceGeometry(kind: string, values: Object[]): void {
    const creates: (() => (() => void))[] = []
    values.forEach(value => {
      const item = new DMPMap(value)
      const stroke = mapColor(item.getString('color') ?? item.getString('strokeColor') ?? '#000000')
      const fill = mapColor(item.getString('fillColor') ?? '#00000000')
      const width = this.pixels(item.getNumber('width') ?? item.getNumber('strokeWidth') ?? 1)
      if (kind === 'circles') {
        const radius = item.getNumber('radius') ?? -1
        if (!Number.isFinite(radius) || radius < 0) throw new Error('Invalid radius')
        const options = new CircleOptions().setCenter(point(item)).setRadius(radius).setStrokeColor(stroke).setFillColor(fill).setStrokeWidth(width)
        creates.push(() => { const overlay = this.map?.addCircle(options); return () => overlay?.remove() })
      } else {
        const points = (item.getArray<Object>('points') ?? []).map(value => point(new DMPMap(value)))
        if (points.length < (kind === 'polyline' ? 2 : 3)) throw new Error('Invalid geometry points')
        const list = new ArrayList<LatLng>(); points.forEach(value => list.add(value))
        if (kind === 'polyline') {
          const options = new PolylineOptions().addAll(list).setColor(stroke).setWidth(width).setDottedLine(item.getBoolean('dottedLine') ?? false)
          creates.push(() => { const overlay = this.map?.addPolyline(options); return () => overlay?.remove() })
        } else {
          const options = new PolygonOptions().setStrokeColor(stroke).setFillColor(fill).setStrokeWidth(width)
          options.setPoints(list)
          creates.push(() => { const overlay = this.map?.addPolygon(options); return () => overlay?.remove() })
        }
      }
    })
    this.geometry.get(kind)?.forEach(remove => remove())
    this.geometry.set(kind, creates.map(create => create()))
  }
  private addMarkers(values: Object[], clear: boolean): void {
    const options: Map<number, MarkerOptions> = new Map()
    const anonymousOptions: MarkerOptions[] = []
    values.forEach(value => {
      const item = new DMPMap(value)
      const id = item.getNumber('id')
      const option = new MarkerOptions().setPosition(point(item)).setTitle(item.getString('title') ?? '')
        .setSnippet(new DMPMap(item.get('callout')).getString('content') ?? '')
        .setRotateAngle(-(item.getNumber('rotate') ?? 0)).setZIndex(item.getNumber('zIndex') ?? 0)
      if (item.get('id') !== undefined) {
        if (id === undefined || !Number.isInteger(id) || options.has(id)) throw new Error('Marker id must be a unique integer')
        options.set(id, option)
      } else {
        anonymousOptions.push(option)
      }
    })
    if (clear) {
      Array.from(this.motions.keys()).forEach(id => this.cancelMotion(id))
      this.markers.forEach(marker => marker.remove()); this.markers.clear(); this.markerValues.clear()
      this.anonymousMarkers.forEach(marker => marker.remove()); this.anonymousMarkers = []; this.anonymousMarkerValues = []
    }
    values.forEach(value => {
      const item = new DMPMap(value)
      if (item.get('id') === undefined) this.anonymousMarkerValues.push(value)
      else this.markerValues.set(item.getNumber('id')!, value)
    })
    options.forEach((value, id) => {
      this.cancelMotion(id)
      this.markers.get(id)?.remove()
      const marker = this.map?.addMarker(value)
      if (marker) this.markers.set(id, marker)
    })
    anonymousOptions.forEach(value => {
      const marker = this.map?.addMarker(value)
      if (marker) this.anonymousMarkers.push(marker)
    })
  }
  private fit(values: Object[], padding: number[]): void {
    if (!values.length || (padding.length !== 0 && padding.length !== 4) || padding.some(n => !Number.isFinite(n) || n < 0)) throw new Error('Invalid points or padding')
    const bounds = LatLngBounds.builder()
    values.forEach(value => bounds.include(point(new DMPMap(value))))
    this.map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), Math.max(0, ...padding)))
  }
  detach(): void {
    Array.from(this.motions.keys()).forEach(id => this.cancelMotion(id))
    this.arcs.clear(); this.geometry.clear()
    if (this.map) this.lastCamera = this.map.getCameraPosition()
    this.locationGeneration++
    this.pendingLocation = undefined
    this.rejectLocation?.(new Error('Map view detached'))
    this.rejectLocation = undefined
    this.map?.setMyLocationEnabled(false)
    this.view?.onPause()
    this.view?.onDestroy()
    this.markers.clear()
    this.anonymousMarkers = []
    this.map = undefined
    this.view = undefined
  }
  destroy(): void {
    if (this.destroyed) return
    this.destroyed = true
    MapViewManager.getInstance().unregisterMapViewCreatedCallback(this.createdCallback)
    this.detach()
    this.markerValues.clear()
    this.arcValues.clear()
    this.anonymousMarkerValues = []
  }
}
function point(value: DMPMap): LatLng {
  const longitude = value.getNumber('longitude')
  const latitude = value.getNumber('latitude')
  if (longitude === undefined || latitude === undefined || !Number.isFinite(longitude) || !Number.isFinite(latitude)
    || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) throw new Error('Invalid coordinate')
  return new LatLng(latitude, longitude)
}
function coordinate(value: LatLng): DMPMap { return new DMPMap({ longitude: value.longitude, latitude: value.latitude }) }

function mapColor(value: string): number {
  if (!/^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(value)) throw new Error('Invalid color')
  return value.length === 9 ? Number.parseInt(value.slice(7) + value.slice(1, 7), 16) : Number.parseInt('ff' + value.slice(1), 16)
}
class MotionSample {
  position: LatLng
  rotate: number
  distance: number
  constructor(position: LatLng, rotate: number, distance: number) { this.position = position; this.rotate = rotate; this.distance = distance }
}
class MarkerMotion {
  private path: LatLng[]
  private lengths: number[] = [0]
  private angle: number
  private rotate: number
  private autoRotate: boolean
  private separate: boolean
  constructor(path: LatLng[], angle: number, rotate: number, autoRotate: boolean, separate: boolean) {
    this.path = path; this.angle = angle; this.rotate = rotate; this.autoRotate = autoRotate; this.separate = separate
    const r = Math.PI / 180
    for (let i = 1; i < path.length; i++) {
      const a = path[i - 1]; const b = path[i]
      const h = Math.sin((b.latitude - a.latitude) * r / 2) ** 2 + Math.cos(a.latitude * r) * Math.cos(b.latitude * r) * Math.sin(this.delta(b.longitude - a.longitude) * r / 2) ** 2
      this.lengths.push(this.lengths[i - 1] + 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h))))
    }
  }
  sample(progress: number): MotionSample {
    const rotation = this.separate ? Math.min(1, progress * 2) : progress
    const movement = this.separate ? Math.max(0, progress * 2 - 1) : progress
    const total = this.lengths[this.lengths.length - 1]; const traveled = total * movement
    let low = 1; let high = this.path.length - 1
    while (low < high) { const mid = (low + high) >> 1; if (this.lengths[mid] < traveled) low = mid + 1; else high = mid }
    const a = this.path[low - 1]; const b = this.path[low]
    const fraction = this.lengths[low] === this.lengths[low - 1] ? movement : (traveled - this.lengths[low - 1]) / (this.lengths[low] - this.lengths[low - 1])
    const r = Math.PI / 180; const dx = this.delta(b.longitude - a.longitude) * r
    const bearing = Math.atan2(Math.sin(dx) * Math.cos(b.latitude * r), Math.cos(a.latitude * r) * Math.sin(b.latitude * r) - Math.sin(a.latitude * r) * Math.cos(b.latitude * r) * Math.cos(dx)) / r
    return new MotionSample(new LatLng(a.latitude + (b.latitude - a.latitude) * fraction,
      movement === 1 ? b.longitude : this.delta(a.longitude + this.delta(b.longitude - a.longitude) * fraction)),
      this.autoRotate && movement > 0 && total > 0 ? (bearing + 360) % 360 : this.angle + this.delta(this.rotate - this.angle) * rotation, traveled)
  }
  private delta(value: number): number { return ((value + 540) % 360 + 360) % 360 - 180 }
}
