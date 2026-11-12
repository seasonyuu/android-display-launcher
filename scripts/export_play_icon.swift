// Mechanical export only: resize and encode generated artwork as opaque 32-bit sRGB PNG.
// Usage: swift scripts/export_play_icon.swift <source.png> <output.png>
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

precondition(CommandLine.arguments.count == 3, "Expected source and destination paths")
let sourceURL = URL(fileURLWithPath: CommandLine.arguments[1])
let outputURL = URL(fileURLWithPath: CommandLine.arguments[2])
let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil)!
let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
precondition(image.width == image.height, "Square source required; no cropping performed")
let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
let context = CGContext(data: nil, width: 512, height: 512, bitsPerComponent: 8,
    bytesPerRow: 512 * 4, space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)!
context.interpolationQuality = .high
context.draw(image, in: CGRect(x: 0, y: 0, width: 512, height: 512))
let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
precondition((0..<(512 * 512)).allSatisfy { pixels[$0 * 4 + 3] == 255 }, "Play icon must be fully opaque")
let destination = CGImageDestinationCreateWithURL(outputURL as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, context.makeImage()!, [kCGImagePropertyPNGDictionary: [kCGImagePropertyPNGsRGBIntent: 0]] as CFDictionary)
precondition(CGImageDestinationFinalize(destination), "PNG export failed")
print("Exported 512×512 opaque RGBA sRGB PNG: \(outputURL.path)")
