
#import "ListLibraryItems.h"

#import <Photos/Photos.h>
#import <MobileCoreServices/MobileCoreServices.h>

static NSString * PERMISSION_ERROR = @"Permission Denial: This application is not allowed to access Photo data.";


@interface ListLibraryItems () {
    CDVInvokedUrlCommand * mCommand;
    NSURL * mLocalTempURL;
    NSDictionary * mReceivedData;
}

@end


@implementation ListLibraryItems
 
- (void)pluginInitialize {
	// Plugin specific initialize login goes here
}


- (BOOL)checkAuthorization {
    return ([PHPhotoLibrary authorizationStatus] == PHAuthorizationStatusAuthorized);
}


- (void)isAuthorized:(CDVInvokedUrlCommand *)command {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:[self checkAuthorization]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    });
}


- (void)returnUserAuthorization:(BOOL)aAuthorized message:(NSString *)aMessage command:(CDVInvokedUrlCommand *)aCommand {
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    if(aAuthorized == NO) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:aMessage];
    }
    [[self commandDelegate] sendPluginResult:pluginResult callbackId:[aCommand callbackId]];
}


- (void)requestReadAuthorization:(CDVInvokedUrlCommand *)command {
    
    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatus];
    switch (status) {
        case PHAuthorizationStatusAuthorized: {
            [self returnUserAuthorization:YES message:nil command:command];
            
        } break;
        case PHAuthorizationStatusNotDetermined: {
            [PHPhotoLibrary requestAuthorization:^(PHAuthorizationStatus status) {
                if(status == PHAuthorizationStatusAuthorized) {
                    [self returnUserAuthorization:YES message:nil command:command];
                }
                else {
                    [self returnUserAuthorization:NO message:@"requestAuthorization denied by user" command:command];
                }
            }];
        } break;
        case PHAuthorizationStatusDenied: {
            NSURL * url = [NSURL URLWithString:UIApplicationLaunchOptionsURLKey];
            if([[UIApplication sharedApplication] openURL:url]) {
                // TODO: run callback only when return ?
                // Do not call success, as the app will be restarted when user changes permission
            }
            else {
                [self returnUserAuthorization:NO message:@"could not open settings url" command:command];
            }
        } break;
        case PHAuthorizationStatusRestricted: {
            [self returnUserAuthorization:NO message:@"requestAuthorization status restricted" command:command];
        }
    }
}


- (void)returnUploadResult:(BOOL)aSuccess payload:(NSDictionary *)aJSON command:(CDVInvokedUrlCommand *)aCommand {
    if(aJSON == nil) {
        aJSON = [NSDictionary dictionary];
    }
    CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:(aSuccess ? CDVCommandStatus_OK : CDVCommandStatus_ERROR) messageAsDictionary:aJSON];
    [[self commandDelegate] sendPluginResult:pluginResult callbackId:[aCommand callbackId]];
}


- (void)uploadItem:(CDVInvokedUrlCommand *)command {
    
    NSDictionary * payload = command.arguments[0];
    NSString * uploadUrl  = payload[@"serverUrl"];
    NSDictionary * headers = payload[@"headers"];
    NSString * libraryId = payload[@"libraryId"];
	
    mCommand = command;
    
    // try to fetch asset
    PHFetchResult<PHAsset *> * assets = [PHAsset fetchAssetsWithLocalIdentifiers:@[libraryId] options:kNilOptions];
    if([assets count] == 0) {
        // asset id not found
        NSString * message = [NSString stringWithFormat:@"Cannot fetch asset %@", libraryId];
        NSMutableDictionary * json = [NSMutableDictionary dictionaryWithObjects:@[@"-1", message, libraryId, uploadUrl] forKeys:@[@"code", @"message", @"source", @"target"]];
        [self returnUploadResult:NO payload:json command:command];
    }
    else {
        for(PHAsset * asset in assets) {
            PHAssetResource * resource = [[PHAssetResource assetResourcesForAsset:asset] firstObject];;
            NSString * temp_path = [NSTemporaryDirectory() stringByAppendingString:[resource originalFilename]];
            mLocalTempURL = [NSURL fileURLWithPath:temp_path];
            [[NSFileManager defaultManager] removeItemAtURL:mLocalTempURL error:nil]; // cleanup
            PHAssetResourceRequestOptions * options = [PHAssetResourceRequestOptions new];
            [options setNetworkAccessAllowed: YES];
            [options setProgressHandler:^(double progress) {
                NSLog(@"progress %f", progress);
            }];
            
            [[PHAssetResourceManager defaultManager] writeDataForAssetResource:resource toFile:mLocalTempURL options:options completionHandler:^(NSError * _Nullable aError) {
                if (aError) {
                    // cannot fetch asset
                    NSString * message = [NSString stringWithFormat:@"Cannot fetch asset %@ (%@)", libraryId, [aError localizedDescription]];
                    NSMutableDictionary * json = [NSMutableDictionary dictionaryWithObjects:@[@"-1", message, libraryId, uploadUrl] forKeys:@[@"code", @"message", @"source", @"target"]];
                    [self returnUploadResult:NO payload:json command:command];
                }
                else {
                    
                    // create upload session
                    NSURLSessionConfiguration * config = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:libraryId];
                    [config setDiscretionary:YES]; // iOS will perform background upload when better suited
                    [config setSessionSendsLaunchEvents:YES]; // launch app when upload finishes, calls "handleEventsForBackgroundURLSession" in AppDelegate
                    
//                    NSURLSessionConfiguration * config = [NSURLSessionConfiguration defaultSessionConfiguration];
                    NSURLSession * session = [NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:[NSOperationQueue mainQueue]];

                    NSMutableURLRequest * request = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:uploadUrl]];
                    [request setHTTPMethod: @"POST"];
                    
                    for(NSString * header in [headers allKeys]) {
                        [request setValue:[headers objectForKey:header] forHTTPHeaderField:header];
                    }

                    NSURLSessionUploadTask * task = [session uploadTaskWithRequest:request fromFile:mLocalTempURL];
                    [task resume];
                }
            }];
        }
    }
}


- (void)listItems:(CDVInvokedUrlCommand *)command {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        // check permissions
        if([self checkAuthorization] == NO) {
            CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:PERMISSION_ERROR];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        }
        else {
            // parse options
            BOOL includePictures = [[command argumentAtIndex:0 withDefault:[NSNumber numberWithBool:YES]] boolValue];
            BOOL includeVideos = [[command argumentAtIndex:1 withDefault:[NSNumber numberWithBool:YES]] boolValue];
            BOOL includeCloud = [[command argumentAtIndex:2 withDefault:[NSNumber numberWithBool:NO]] boolValue];
            
            // fetch library
            PHFetchOptions * options = [[PHFetchOptions alloc] init];
            [options setIncludeHiddenAssets:NO];
            [options setIncludeAllBurstAssets:NO]; // TODO: add bursts ?
            [options setFetchLimit:0];
            NSSortDescriptor * sort_desc = [NSSortDescriptor sortDescriptorWithKey:@"creationDate" ascending:YES];
            [options setSortDescriptors:@[sort_desc]];
            
            [options setIncludeAssetSourceTypes:PHAssetSourceTypeUserLibrary |
                                                PHAssetSourceTypeiTunesSynced /* TODO: keep itunes stuff ? */ |
                                                (includeCloud ? PHAssetSourceTypeCloudShared : PHAssetSourceTypeNone)];
            
            NSPredicate * predicate = nil;
            if(includePictures == YES && includeVideos == true) {
                predicate = [NSPredicate predicateWithFormat:@"mediaType == %d || mediaType == %d", PHAssetMediaTypeImage, PHAssetMediaTypeVideo];
            }
            else {
                if(includePictures == YES) {
                    predicate = [NSPredicate predicateWithFormat:@"mediaType == %d", PHAssetMediaTypeImage];
                }
                else if(includeVideos == YES) {
                    predicate = [NSPredicate predicateWithFormat:@"mediaType == %d", PHAssetMediaTypeVideo];
                }
            }
            [options setPredicate:predicate];
            
            NSDateFormatter * df = [[NSDateFormatter alloc] init];
            [df setDateFormat:@"yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ"];

            // loop thru assets and build data
            PHFetchResult * assets = [PHAsset fetchAssetsWithOptions:options];
            NSMutableArray * items = [NSMutableArray array];
            for(PHAsset * asset in assets) {
                
                NSMutableDictionary * item = [NSMutableDictionary dictionary];
                NSString * file_name = [asset valueForKey:@"filename"];
                
                [item setObject:[asset localIdentifier] forKey:@"id"];
                [item setObject:file_name forKey:@"fileName"];
                [item setObject:[NSNumber numberWithUnsignedInteger:[asset pixelWidth]] forKey:@"width"];
                [item setObject:[NSNumber numberWithUnsignedInteger:[asset pixelHeight]] forKey:@"height"];
                [item setObject:[self getMimeTypeFromPath:file_name] forKey:@"mimeType"];
                [item setObject:[df stringFromDate:[asset creationDate]] forKey:@"creationDate"];
//                if([asset location]) {
//                    [item setObject:[asset localIdentifier] forKey:@"latitude"];
//                    [item setObject:[asset localIdentifier] forKey:@"longitude"];
//                }
                [items addObject:item];
            }
            
            // send result to js
            NSDictionary * result = [NSDictionary dictionaryWithObjects:@[[NSNumber numberWithUnsignedInteger:[items count]], items]
                                                                forKeys:@[@"count", @"library"]];
            CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
            [[self commandDelegate] sendPluginResult:pluginResult callbackId:[command callbackId]];
        }
    });
}


- (NSString *)getMimeTypeFromPath:(NSString*)fullPath {
    NSString * mimeType = @"application/octet-stream";
    if (fullPath) {
        CFStringRef typeId = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, (__bridge CFStringRef)[fullPath pathExtension], NULL);
        if (typeId) {
            mimeType = (__bridge_transfer NSString*)UTTypeCopyPreferredTagWithClass(typeId, kUTTagClassMIMEType);
            if (!mimeType) {
                // special case for m4a
                if ([(__bridge NSString*)typeId rangeOfString : @"m4a-audio" options:NSCaseInsensitiveSearch].location != NSNotFound) {
                    mimeType = @"audio/mp4";
                } else if ([[fullPath pathExtension] rangeOfString:@"wav" options:NSCaseInsensitiveSearch].location != NSNotFound) {
                    mimeType = @"audio/wav"; options:NSCaseInsensitiveSearch
                } else if ([[fullPath pathExtension] rangeOfString:@"css" options:NSCaseInsensitiveSearch].location != NSNotFound) {
                    mimeType = @"text/css";
                } else if ([[fullPath pathExtension] rangeOfString:@"heic" options:NSCaseInsensitiveSearch].location != NSNotFound) {
                    mimeType = @"image/heic";
                } else if ([[fullPath pathExtension] rangeOfString:@"heif" options:NSCaseInsensitiveSearch].location != NSNotFound) {
                    mimeType = @"image/heif";
                } else {
                    mimeType = @"application/octet-stream";
                }
            }
            CFRelease(typeId);
        }
    }
    return mimeType;
}


#pragma mark - NSURLSessionDelegate
//- (void)URLSession:(NSURLSession *)session didBecomeInvalidWithError:(NSError *)error {
//    NSLog(@"didBecomeInvalidWithError");
//}

- (void)URLSession:(NSURLSession *)session didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
                                             completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential * _Nullable))completionHandler {
    
    NSLog(@"didReceiveChallenge");
    NSURLCredential * credential = [NSURLCredential credentialForTrust:challenge.protectionSpace.serverTrust];
    completionHandler(NSURLSessionAuthChallengeUseCredential, credential);
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    NSLog(@"URLSessionDidFinishEventsForBackgroundURLSession");
}

#pragma mark - NSURLSessionTaskDelegate
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didSendBodyData:(int64_t)bytesSent totalBytesSent:(int64_t)totalBytesSent totalBytesExpectedToSend:(int64_t)totalBytesExpectedToSend {
    NSLog(@"didSendBodyData: send %lld / %lld", totalBytesSent, totalBytesExpectedToSend);
    // TODO: call JS
    
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    NSHTTPURLResponse * response = (NSHTTPURLResponse *)[task response];
    long status = (long)[response statusCode];
    
    // cleanup
    [[NSFileManager defaultManager] removeItemAtURL:mLocalTempURL error:nil];
    NSString * target = [[[task originalRequest] URL] relativeString];
    if(error || (status / 100) != 2) {
        NSString * errorMessage;
        
        if (error) {
            NSLog(@"Error: %@", error);
            errorMessage = [error localizedDescription];
        }
        else {
            errorMessage = [NSHTTPURLResponse localizedStringForStatusCode:status];
        }
        NSLog(@"Error: %@", error);
        NSMutableDictionary * json = [NSMutableDictionary dictionaryWithObjects:@[[NSString stringWithFormat:@"%ld", status], errorMessage, [mLocalTempURL absoluteString], target]
                                                                        forKeys:@[@"code", @"message", @"source", @"target"]];
        [self returnUploadResult:NO payload:json command:mCommand];
    } else {
        NSLog(@"--- STATUS OK ---");
        NSLog(@"%@", response);
        [self returnUploadResult:YES payload:mReceivedData command:mCommand];
    }
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    if (data == nil) {
        mReceivedData = [NSMutableDictionary dictionary];
    }
    else {
        id json =[NSJSONSerialization JSONObjectWithData:data options:kNilOptions error:nil];
        if([json isKindOfClass:[NSDictionary class]]) {
            mReceivedData = (NSDictionary *)json;
        }
    }
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task needNewBodyStream:(nonnull void (^)(NSInputStream * _Nullable))completionHandler {
    NSLog(@"needNewBodyStream");
}

@end
